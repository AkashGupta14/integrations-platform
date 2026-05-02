package com.integrations.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrations.model.IntegrationItem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Service
public class NotionService {

    private final RedisService redisService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notion.client-id}")
    private String clientId;

    @Value("${notion.client-secret}")
    private String clientSecret;

    private static final String REDIRECT_URI = "http://localhost:8000/integrations/notion/oauth2callback";
    private static final String TOKEN_URL = "https://api.notion.com/v1/oauth/token";

    private String encodedClientIdSecret;

    public NotionService(RedisService redisService, RestTemplate restTemplate, ObjectMapper objectMapper) {
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

        String encodedState = objectMapper.writeValueAsString(stateData);
        redisService.set("notion_state:" + orgId + ":" + userId, encodedState, 600);

        return "https://api.notion.com/v1/oauth/authorize"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&owner=user"
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A8000%2Fintegrations%2Fnotion%2Foauth2callback"
                + "&state=" + encodedState;
    }

    public String oauth2callback(String code, String encodedState, String error) throws Exception {
        if (error != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }

        Map<String, String> stateData = objectMapper.readValue(encodedState, new TypeReference<>() {});
        String originalState = stateData.get("state");
        String userId = stateData.get("user_id");
        String orgId = stateData.get("org_id");

        String savedStateJson = redisService.get("notion_state:" + orgId + ":" + userId);
        if (savedStateJson == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        Map<String, String> savedStateData = objectMapper.readValue(savedStateJson, new TypeReference<>() {});
        if (!originalState.equals(savedStateData.get("state"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        // Exchange code for token (Notion uses JSON body, not form-encoded)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodedClientIdSecret);

        Map<String, String> body = Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);

        redisService.delete("notion_state:" + orgId + ":" + userId);
        redisService.set("notion_credentials:" + orgId + ":" + userId, response.getBody(), 600);

        return "<html><script>window.close();</script></html>";
    }

    public Object getCredentials(String userId, String orgId) throws Exception {
        String credentials = redisService.get("notion_credentials:" + orgId + ":" + userId);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No credentials found.");
        }
        redisService.delete("notion_credentials:" + orgId + ":" + userId);
        return objectMapper.readValue(credentials, Object.class);
    }

    @SuppressWarnings("unchecked")
    public List<IntegrationItem> getItems(String credentials) throws Exception {
        Map<String, Object> creds = objectMapper.readValue(credentials, new TypeReference<>() {});
        String accessToken = (String) creds.get("access_token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("Notion-Version", "2022-06-28");

        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.notion.com/v1/search", request, String.class);

        List<IntegrationItem> items = new ArrayList<>();

        if (response.getStatusCode() == HttpStatus.OK) {
            Map<String, Object> data = objectMapper.readValue(
                    response.getBody(), new TypeReference<>() {});
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");

            if (results != null) {
                for (Map<String, Object> result : results) {
                    items.add(createItemMetadata(result));
                }
            }
        }

        System.out.println("list_of_integration_item_metadata: " + items);
        return items;
    }

    @SuppressWarnings("unchecked")
    private IntegrationItem createItemMetadata(Map<String, Object> json) {
        Map<String, Object> properties = (Map<String, Object>) json.get("properties");
        String name = recursiveDictSearch(properties, "content");

        Map<String, Object> parent = (Map<String, Object>) json.get("parent");
        String parentType = parent.get("type") != null ? (String) parent.get("type") : "";
        String parentId = null;
        if (!"workspace".equals(parent.get("type"))) {
            Object pid = parent.get(parentType);
            parentId = pid != null ? pid.toString() : null;
        }

        if (name == null) {
            name = recursiveDictSearch(json, "content");
        }
        if (name == null) {
            name = "multi_select";
        }
        name = json.get("object") + " " + name;

        return IntegrationItem.builder()
                .id((String) json.get("id"))
                .type((String) json.get("object"))
                .name(name)
                .creationTime((String) json.get("created_time"))
                .lastModifiedTime((String) json.get("last_edited_time"))
                .parentId(parentId)
                .build();
    }

    @SuppressWarnings("unchecked")
    private String recursiveDictSearch(Map<String, Object> data, String targetKey) {
        if (data == null) return null;
        if (data.containsKey(targetKey)) {
            Object value = data.get(targetKey);
            return value != null ? value.toString() : null;
        }
        for (Object value : data.values()) {
            if (value instanceof Map) {
                String result = recursiveDictSearch((Map<String, Object>) value, targetKey);
                if (result != null) return result;
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item instanceof Map) {
                        String result = recursiveDictSearch((Map<String, Object>) item, targetKey);
                        if (result != null) return result;
                    }
                }
            }
        }
        return null;
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
