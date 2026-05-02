# hubspot.py

import json
import os
import secrets
from fastapi import Request, HTTPException
from fastapi.responses import HTMLResponse
import httpx
import asyncio
import requests
from integrations.integration_item import IntegrationItem

from redis_client import add_key_value_redis, get_value_redis, delete_key_redis

CLIENT_ID = os.environ.get('HUBSPOT_CLIENT_ID', 'YOUR_HUBSPOT_CLIENT_ID')
CLIENT_SECRET = os.environ.get('HUBSPOT_CLIENT_SECRET', 'YOUR_HUBSPOT_CLIENT_SECRET')
REDIRECT_URI = 'http://localhost:8000/integrations/hubspot/oauth2callback'
SCOPES = 'crm.objects.contacts.read crm.objects.companies.read crm.objects.deals.read oauth'

authorization_url = (
    f'https://app.hubspot.com/oauth/authorize'
    f'?client_id={CLIENT_ID}'
    f'&redirect_uri={REDIRECT_URI}'
    f'&scope={SCOPES.replace(" ", "%20")}'
)


async def authorize_hubspot(user_id, org_id):
    state_data = {
        'state': secrets.token_urlsafe(32),
        'user_id': user_id,
        'org_id': org_id,
    }
    encoded_state = json.dumps(state_data)
    await add_key_value_redis(
        f'hubspot_state:{org_id}:{user_id}', encoded_state, expire=600
    )

    return f'{authorization_url}&state={encoded_state}'


async def oauth2callback_hubspot(request: Request):
    if request.query_params.get('error'):
        raise HTTPException(
            status_code=400,
            detail=request.query_params.get('error_description', 'OAuth error'),
        )
    code = request.query_params.get('code')
    encoded_state = request.query_params.get('state')
    state_data = json.loads(encoded_state)

    original_state = state_data.get('state')
    user_id = state_data.get('user_id')
    org_id = state_data.get('org_id')

    saved_state = await get_value_redis(f'hubspot_state:{org_id}:{user_id}')

    if not saved_state or original_state != json.loads(saved_state).get('state'):
        raise HTTPException(status_code=400, detail='State does not match.')

    async with httpx.AsyncClient() as client:
        response, _ = await asyncio.gather(
            client.post(
                'https://api.hubapi.com/oauth/v1/token',
                data={
                    'grant_type': 'authorization_code',
                    'client_id': CLIENT_ID,
                    'client_secret': CLIENT_SECRET,
                    'redirect_uri': REDIRECT_URI,
                    'code': code,
                },
                headers={
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
            ),
            delete_key_redis(f'hubspot_state:{org_id}:{user_id}'),
        )

    await add_key_value_redis(
        f'hubspot_credentials:{org_id}:{user_id}',
        json.dumps(response.json()),
        expire=600,
    )

    close_window_script = """
    <html>
        <script>
            window.close();
        </script>
    </html>
    """
    return HTMLResponse(content=close_window_script)


async def get_hubspot_credentials(user_id, org_id):
    credentials = await get_value_redis(f'hubspot_credentials:{org_id}:{user_id}')
    if not credentials:
        raise HTTPException(status_code=400, detail='No credentials found.')
    credentials = json.loads(credentials)
    await delete_key_redis(f'hubspot_credentials:{org_id}:{user_id}')

    return credentials


def create_integration_item_metadata_object(
    response_json: dict,
    item_type: str,
    name: str,
    parent_id=None,
    parent_name=None,
) -> IntegrationItem:
    properties = response_json.get('properties', {})
    creation_time = properties.get('createdate')
    last_modified_time = properties.get('hs_lastmodifieddate') or properties.get('lastmodifieddate')
    obj_id = response_json.get('id')

    return IntegrationItem(
        id=f'{obj_id}_{item_type}',
        type=item_type,
        name=name,
        creation_time=creation_time,
        last_modified_time=last_modified_time,
        url=f'https://app.hubspot.com/contacts/{obj_id}',
        parent_id=parent_id,
        parent_path_or_name=parent_name,
    )


def _fetch_hubspot_objects(access_token, object_type, properties):
    """Fetch all objects of a given type from HubSpot CRM, handling pagination."""
    url = f'https://api.hubapi.com/crm/v3/objects/{object_type}'
    headers = {'Authorization': f'Bearer {access_token}'}
    params = {
        'limit': 100,
        'properties': ','.join(properties),
    }
    all_results = []

    while True:
        response = requests.get(url, headers=headers, params=params)
        if response.status_code != 200:
            break
        data = response.json()
        all_results.extend(data.get('results', []))
        paging = data.get('paging')
        if paging and paging.get('next', {}).get('after'):
            params['after'] = paging['next']['after']
        else:
            break

    return all_results


async def get_items_hubspot(credentials) -> list[IntegrationItem]:
    credentials = json.loads(credentials)
    access_token = credentials.get('access_token')
    list_of_integration_item_metadata = []

    # Fetch contacts
    contacts = _fetch_hubspot_objects(
        access_token, 'contacts', ['firstname', 'lastname', 'email', 'createdate', 'lastmodifieddate']
    )
    for contact in contacts:
        props = contact.get('properties', {})
        first = (props.get('firstname') or '').strip()
        last = (props.get('lastname') or '').strip()
        name = f'{first} {last}'.strip() or props.get('email') or f'Contact {contact["id"]}'
        list_of_integration_item_metadata.append(
            create_integration_item_metadata_object(contact, 'Contact', name)
        )

    # Fetch companies — build a lookup so deals can reference their company
    companies = _fetch_hubspot_objects(
        access_token, 'companies', ['name', 'domain', 'createdate', 'hs_lastmodifieddate']
    )
    company_names = {}
    for company in companies:
        props = company.get('properties', {})
        name = props.get('name') or props.get('domain') or f'Company {company["id"]}'
        company_names[company['id']] = name
        list_of_integration_item_metadata.append(
            create_integration_item_metadata_object(company, 'Company', name)
        )

    # Fetch deals
    deals = _fetch_hubspot_objects(
        access_token, 'deals', ['dealname', 'amount', 'dealstage', 'createdate', 'hs_lastmodifieddate']
    )
    for deal in deals:
        props = deal.get('properties', {})
        name = props.get('dealname') or f'Deal {deal["id"]}'
        list_of_integration_item_metadata.append(
            create_integration_item_metadata_object(deal, 'Deal', name)
        )

    print(f'list_of_integration_item_metadata: {list_of_integration_item_metadata}')
    return list_of_integration_item_metadata
