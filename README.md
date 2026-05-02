# Integrations Platform

A full-stack integrations platform built with **FastAPI**, **React**, and **Redis** that connects to third-party services via OAuth 2.0 and loads structured data from their APIs.

## Supported Integrations

| Integration | OAuth | Data Loading | Objects |
|------------|-------|-------------|---------|
| **Airtable** | PKCE flow | Bases, Tables | Hierarchical (Table → Base) |
| **Notion** | Authorization code | Pages, Databases | Workspace tree |
| **HubSpot** | Authorization code | Contacts, Companies, Deals | CRM objects |

## Architecture

```
┌─────────────┐      ┌──────────────┐      ┌───────┐
│  React SPA  │─────▶│ FastAPI API  │─────▶│ Redis │
│  (MUI + Axios)     │  (OAuth +    │      │(state │
│  :3000      │◀─────│   CRM fetch) │      │+creds)│
└─────────────┘      └──────────────┘      └───────┘
                            │
                     ┌──────┴──────┐
                     ▼             ▼
              Third-party    Third-party
              OAuth servers  Data APIs
```

- **Frontend**: React + Material UI — renders an integration selector, drives the OAuth popup, and displays loaded data.
- **Backend**: Python FastAPI — handles OAuth handshakes, stores ephemeral state/credentials in Redis, and fetches data from provider APIs.
- **Redis**: Used as a short-lived key-value store for CSRF `state` tokens and OAuth credentials during the popup flow.

## Prerequisites

- Python 3.10+
- Node.js 16+
- Redis server

## Setup

### 1. Register a HubSpot App

1. Create a free developer account at https://developers.hubspot.com.
2. Create a new app → **Auth** tab → set redirect URL to:
   ```
   http://localhost:8000/integrations/hubspot/oauth2callback
   ```
3. Add scopes: `crm.objects.contacts.read`, `crm.objects.companies.read`, `crm.objects.deals.read`, `oauth`.
4. Copy the **Client ID** and **Client Secret**.

### 2. Configure Environment

```bash
cp .env.example .env
# Fill in HUBSPOT_CLIENT_ID and HUBSPOT_CLIENT_SECRET
```

Or export directly:

```bash
export HUBSPOT_CLIENT_ID=your_client_id
export HUBSPOT_CLIENT_SECRET=your_client_secret
```

### 3. Start Redis

```bash
redis-server
```

### 4. Start the Backend

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

API runs on http://localhost:8000.

### 5. Start the Frontend

```bash
cd frontend
npm install
npm start
```

App opens at http://localhost:3000.

## Usage

1. Open http://localhost:3000.
2. Select an integration (e.g. **HubSpot**) from the dropdown.
3. Click **Connect to HubSpot** — a popup opens for OAuth consent.
4. Approve access — popup closes, button turns green.
5. Click **Load Data** — CRM objects are fetched and displayed.

## Project Structure

```
backend/
  main.py                          # FastAPI routes for all integrations
  redis_client.py                  # Async Redis helper functions
  integrations/
    integration_item.py            # IntegrationItem data model
    airtable.py                    # Airtable OAuth + data loading
    notion.py                      # Notion OAuth + data loading
    hubspot.py                     # HubSpot OAuth + CRM data loading
frontend/
  src/
    App.js                         # Root component
    integration-form.js            # Integration selector + OAuth trigger
    data-form.js                   # Load Data button + display
    integrations/
      airtable.js                  # Airtable OAuth component
      notion.js                    # Notion OAuth component
      hubspot.js                   # HubSpot OAuth component
```

## HubSpot Implementation Details

The HubSpot integration follows the standard OAuth 2.0 authorization-code grant:

1. **Authorization**: Generates a CSRF `state` token, stores it in Redis, and redirects the user to HubSpot's consent page.
2. **Callback**: Validates the `state` parameter, exchanges the authorization code for tokens via HubSpot's token endpoint (`application/x-www-form-urlencoded`), and stores credentials in Redis.
3. **Data Loading**: Fetches **Contacts**, **Companies**, and **Deals** from the HubSpot CRM v3 API with cursor-based pagination. Each object is mapped to an `IntegrationItem` with `id`, `type`, `name`, `creation_time`, `last_modified_time`, and `url`.

## License

MIT
