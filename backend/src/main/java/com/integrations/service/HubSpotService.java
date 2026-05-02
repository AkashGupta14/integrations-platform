package com.integrations.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrations.model.IntegrationItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.security.SecureRandom;
import java.util.*;

@Service
public class HubSpotService {

    private final RedisService redisService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${hubspot.client-id}")
    private String clientId;

    @Value("${hubspot.client-secret}")
    private String clientSecret;

    private static final String REDIRECT_URI = "http://localhost:8000/integrations/hubspot/oauth2callback";
    private static final String SCOPES = "crm.objects.contacts.read crm.objects.companies.read crm.objects.deals.read oauth";
    private static final String TOKEN_URL = "https://api.hubapi.com/oauth/v1/token";

    public HubSpotService(RedisService redisService, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.redisService = redisService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String authorize(String userId, String orgId) throws Exception {
        Map<String, String> stateData = new LinkedHashMap<>();
        stateData.put("state", generateSecureToken());
        stateData.put("user_id", userId);
        stateData.put("org_id", orgId);

        String encodedState = objectMapper.writeValueAsString(stateData);
        redisService.set("hubspot_state:" + orgId + ":" + userId, encodedState, 600);

        return "https://app.hubspot.com/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=" + REDIRECT_URI
                + "&scope=" + SCOPES.replace(" ", "%20")
                + "&state=" + encodedState;
    }

    public String oauth2callback(String code, String encodedState, String error, String errorDescription)
            throws Exception {
        if (error != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    errorDescription != null ? errorDescription : "OAuth error");
        }

        Map<String, String> stateData = objectMapper.readValue(encodedState, new TypeReference<>() {});
        String originalState = stateData.get("state");
        String userId = stateData.get("user_id");
        String orgId = stateData.get("org_id");

        String savedStateJson = redisService.get("hubspot_state:" + orgId + ":" + userId);
        if (savedStateJson == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        Map<String, String> savedStateData = objectMapper.readValue(savedStateJson, new TypeReference<>() {});
        if (!originalState.equals(savedStateData.get("state"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        // Exchange code for token (form-encoded, credentials in body)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("redirect_uri", REDIRECT_URI);
        body.add("code", code);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);

        redisService.delete("hubspot_state:" + orgId + ":" + userId);
        redisService.set("hubspot_credentials:" + orgId + ":" + userId, response.getBody(), 600);

        return "<html><script>window.close();</script></html>";
    }

    public Object getCredentials(String userId, String orgId) throws Exception {
        String credentials = redisService.get("hubspot_credentials:" + orgId + ":" + userId);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No credentials found.");
        }
        redisService.delete("hubspot_credentials:" + orgId + ":" + userId);
        return objectMapper.readValue(credentials, Object.class);
    }

    @CircuitBreaker(name = "hubspot", fallbackMethod = "getItemsFallback")
    @Retry(name = "hubspot")
    @SuppressWarnings("unchecked")
    public List<IntegrationItem> getItems(String credentials) throws Exception {
        Map<String, Object> creds = objectMapper.readValue(credentials, new TypeReference<>() {});
        String accessToken = (String) creds.get("access_token");

        List<IntegrationItem> items = new ArrayList<>();

        // Fetch contacts
        List<Map<String, Object>> contacts = fetchObjects(accessToken, "contacts",
                List.of("firstname", "lastname", "email", "createdate", "lastmodifieddate"));
        for (Map<String, Object> contact : contacts) {
            Map<String, Object> props = (Map<String, Object>) contact.getOrDefault("properties", Map.of());
            String first = trimOrEmpty((String) props.get("firstname"));
            String last = trimOrEmpty((String) props.get("lastname"));
            String name = (first + " " + last).trim();
            if (name.isEmpty()) {
                name = props.get("email") != null ? (String) props.get("email") : "Contact " + contact.get("id");
            }
            items.add(createItemMetadata(contact, "Contact", name, null, null));
        }

        // Fetch companies
        List<Map<String, Object>> companies = fetchObjects(accessToken, "companies",
                List.of("name", "domain", "createdate", "hs_lastmodifieddate"));
        Map<String, String> companyNames = new HashMap<>();
        for (Map<String, Object> company : companies) {
            Map<String, Object> props = (Map<String, Object>) company.getOrDefault("properties", Map.of());
            String name = (String) props.get("name");
            if (name == null || name.isBlank()) name = (String) props.get("domain");
            if (name == null || name.isBlank()) name = "Company " + company.get("id");
            companyNames.put((String) company.get("id"), name);
            items.add(createItemMetadata(company, "Company", name, null, null));
        }

        // Fetch deals
        List<Map<String, Object>> deals = fetchObjects(accessToken, "deals",
                List.of("dealname", "amount", "dealstage", "createdate", "hs_lastmodifieddate"));
        for (Map<String, Object> deal : deals) {
            Map<String, Object> props = (Map<String, Object>) deal.getOrDefault("properties", Map.of());
            String name = (String) props.get("dealname");
            if (name == null || name.isBlank()) name = "Deal " + deal.get("id");
            items.add(createItemMetadata(deal, "Deal", name, null, null));
        }

        System.out.println("list_of_integration_item_metadata: " + items);
        return items;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchObjects(String accessToken, String objectType,
                                                    List<String> properties) {
        List<Map<String, Object>> allResults = new ArrayList<>();
        String after = null;

        while (true) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromHttpUrl("https://api.hubapi.com/crm/v3/objects/" + objectType)
                    .queryParam("limit", 100)
                    .queryParam("properties", String.join(",", properties));
            if (after != null) {
                uriBuilder.queryParam("after", after);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        uriBuilder.toUriString(), HttpMethod.GET, request, String.class);

                if (response.getStatusCode() != HttpStatus.OK) break;

                Map<String, Object> data = objectMapper.readValue(
                        response.getBody(), new TypeReference<>() {});
                List<Map<String, Object>> results =
                        (List<Map<String, Object>>) data.getOrDefault("results", List.of());
                allResults.addAll(results);

                Map<String, Object> paging = (Map<String, Object>) data.get("paging");
                if (paging != null) {
                    Map<String, Object> next = (Map<String, Object>) paging.get("next");
                    if (next != null && next.get("after") != null) {
                        after = next.get("after").toString();
                        continue;
                    }
                }
                break;
            } catch (Exception e) {
                break;
            }
        }

        return allResults;
    }

    @SuppressWarnings("unchecked")
    private IntegrationItem createItemMetadata(Map<String, Object> json, String itemType,
                                               String name, String parentId, String parentName) {
        Map<String, Object> props = (Map<String, Object>) json.getOrDefault("properties", Map.of());
        String creationTime = (String) props.get("createdate");
        String lastModified = props.get("hs_lastmodifieddate") != null
                ? (String) props.get("hs_lastmodifieddate")
                : (String) props.get("lastmodifieddate");
        String objId = (String) json.get("id");

        return IntegrationItem.builder()
                .id(objId + "_" + itemType)
                .type(itemType)
                .name(name)
                .creationTime(creationTime)
                .lastModifiedTime(lastModified)
                .url("https://app.hubspot.com/contacts/" + objId)
                .parentId(parentId)
                .parentPathOrName(parentName)
                .build();
    }

    @SuppressWarnings("unused")
    private List<IntegrationItem> getItemsFallback(String credentials, Exception e) {
        System.err.println("Circuit breaker open for HubSpot: " + e.getMessage());
        return Collections.emptyList();
    }

    private String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
