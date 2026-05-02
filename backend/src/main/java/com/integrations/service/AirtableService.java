package com.integrations.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrations.model.IntegrationItem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class AirtableService {

    private final RedisService redisService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${airtable.client-id}")
    private String clientId;

    @Value("${airtable.client-secret}")
    private String clientSecret;

    private static final String REDIRECT_URI = "http://localhost:8000/integrations/airtable/oauth2callback";
    private static final String SCOPE = "data.records:read data.records:write data.recordComments:read data.recordComments:write schema.bases:read schema.bases:write";
    private static final String TOKEN_URL = "https://airtable.com/oauth2/v1/token";

    private String encodedClientIdSecret;

    public AirtableService(RedisService redisService, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.redisService = redisService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        encodedClientIdSecret = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    public String authorize(String userId, String orgId) throws Exception {
        Map<String, String> stateData = new LinkedHashMap<>();
        stateData.put("state", generateSecureToken());
        stateData.put("user_id", userId);
        stateData.put("org_id", orgId);

        String stateJson = objectMapper.writeValueAsString(stateData);
        String encodedState = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stateJson.getBytes(StandardCharsets.UTF_8));

        // PKCE code verifier + challenge
        String codeVerifier = generateSecureToken();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        redisService.set("airtable_state:" + orgId + ":" + userId, stateJson, 600);
        redisService.set("airtable_verifier:" + orgId + ":" + userId, codeVerifier, 600);

        return "https://airtable.com/oauth2/v1/authorize"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&owner=user"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                + "&state=" + encodedState
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256"
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
    }

    public String oauth2callback(String code, String encodedState, String error, String errorDescription)
            throws Exception {
        if (error != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorDescription);
        }

        String stateJson = new String(
                Base64.getUrlDecoder().decode(encodedState), StandardCharsets.UTF_8);
        Map<String, String> stateData = objectMapper.readValue(stateJson, new TypeReference<>() {});

        String originalState = stateData.get("state");
        String userId = stateData.get("user_id");
        String orgId = stateData.get("org_id");

        String savedStateJson = redisService.get("airtable_state:" + orgId + ":" + userId);
        String codeVerifier = redisService.get("airtable_verifier:" + orgId + ":" + userId);

        if (savedStateJson == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        Map<String, String> savedStateData = objectMapper.readValue(savedStateJson, new TypeReference<>() {});
        if (!originalState.equals(savedStateData.get("state"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        // Exchange code for token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + encodedClientIdSecret);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", REDIRECT_URI);
        body.add("client_id", clientId);
        body.add("code_verifier", codeVerifier);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);

        // Cleanup and store credentials
        redisService.delete("airtable_state:" + orgId + ":" + userId);
        redisService.delete("airtable_verifier:" + orgId + ":" + userId);
        redisService.set("airtable_credentials:" + orgId + ":" + userId, response.getBody(), 600);

        return "<html><script>window.close();</script></html>";
    }

    public Object getCredentials(String userId, String orgId) throws Exception {
        String credentials = redisService.get("airtable_credentials:" + orgId + ":" + userId);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No credentials found.");
        }
        redisService.delete("airtable_credentials:" + orgId + ":" + userId);
        return objectMapper.readValue(credentials, Object.class);
    }

    @CircuitBreaker(name = "airtable", fallbackMethod = "getItemsFallback")
    @Retry(name = "airtable")
    @SuppressWarnings("unchecked")
    public List<IntegrationItem> getItems(String credentials) throws Exception {
        Map<String, Object> creds = objectMapper.readValue(credentials, new TypeReference<>() {});
        String accessToken = (String) creds.get("access_token");

        List<IntegrationItem> items = new ArrayList<>();
        List<Map<String, Object>> bases = new ArrayList<>();

        fetchBases(accessToken, "https://api.airtable.com/v0/meta/bases", bases, null);

        for (Map<String, Object> base : bases) {
            String baseId = (String) base.get("id");
            String baseName = (String) base.get("name");
            items.add(createItemMetadata(base, "Base", null, null));

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + accessToken);
                HttpEntity<Void> req = new HttpEntity<>(headers);

                ResponseEntity<String> tablesResp = restTemplate.exchange(
                        "https://api.airtable.com/v0/meta/bases/" + baseId + "/tables",
                        HttpMethod.GET, req, String.class);

                if (tablesResp.getStatusCode() == HttpStatus.OK) {
                    Map<String, Object> tablesData = objectMapper.readValue(
                            tablesResp.getBody(), new TypeReference<>() {});
                    List<Map<String, Object>> tables =
                            (List<Map<String, Object>>) tablesData.get("tables");
                    if (tables != null) {
                        for (Map<String, Object> table : tables) {
                            items.add(createItemMetadata(table, "Table", baseId, baseName));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        System.out.println("list_of_integration_item_metadata: " + items);
        return items;
    }

    @SuppressWarnings("unchecked")
    private void fetchBases(String accessToken, String url,
                            List<Map<String, Object>> results, String offset) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        String requestUrl = offset != null ? url + "?offset=" + offset : url;
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> data = objectMapper.readValue(
                        response.getBody(), new TypeReference<>() {});
                List<Map<String, Object>> basesPage =
                        (List<Map<String, Object>>) data.get("bases");
                if (basesPage != null) {
                    results.addAll(basesPage);
                }
                String nextOffset = (String) data.get("offset");
                if (nextOffset != null) {
                    fetchBases(accessToken, url, results, nextOffset);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private IntegrationItem createItemMetadata(Map<String, Object> json, String itemType,
                                               String parentId, String parentName) {
        String id = (String) json.get("id");
        String name = (String) json.get("name");
        return IntegrationItem.builder()
                .id(id + "_" + itemType)
                .name(name)
                .type(itemType)
                .parentId(parentId != null ? parentId + "_Base" : null)
                .parentPathOrName(parentName)
                .build();
    }

    @SuppressWarnings("unused")
    private List<IntegrationItem> getItemsFallback(String credentials, Exception e) {
        System.err.println("Circuit breaker open for Airtable: " + e.getMessage());
        return Collections.emptyList();
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
