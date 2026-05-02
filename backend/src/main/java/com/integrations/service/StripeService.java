package com.integrations.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrations.model.IntegrationItem;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;

@Service
public class StripeService {

    private final RedisService redisService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${stripe.client-id}")
    private String clientId;

    @Value("${stripe.api-key}")
    private String apiKey;

    private static final String REDIRECT_URI = "http://localhost:8000/integrations/stripe/oauth2callback";
    private static final String TOKEN_URL = "https://connect.stripe.com/oauth/token";

    public StripeService(RedisService redisService, RestTemplate restTemplate, ObjectMapper objectMapper) {
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
        redisService.set("stripe_state:" + orgId + ":" + userId, encodedState, 600);

        return "https://connect.stripe.com/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + clientId
                + "&scope=read_write"
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

        String savedStateJson = redisService.get("stripe_state:" + orgId + ":" + userId);
        if (savedStateJson == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        Map<String, String> savedStateData = objectMapper.readValue(savedStateJson, new TypeReference<>() {});
        if (!originalState.equals(savedStateData.get("state"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State does not match.");
        }

        // Exchange code for token (Stripe uses form-encoded with api key as secret)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("client_secret", apiKey);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);

        redisService.delete("stripe_state:" + orgId + ":" + userId);
        redisService.set("stripe_credentials:" + orgId + ":" + userId, response.getBody(), 600);

        return "<html><script>window.close();</script></html>";
    }

    public Object getCredentials(String userId, String orgId) throws Exception {
        String credentials = redisService.get("stripe_credentials:" + orgId + ":" + userId);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No credentials found.");
        }
        redisService.delete("stripe_credentials:" + orgId + ":" + userId);
        return objectMapper.readValue(credentials, Object.class);
    }

    @CircuitBreaker(name = "stripe", fallbackMethod = "getItemsFallback")
    @Retry(name = "stripe")
    @SuppressWarnings("unchecked")
    public List<IntegrationItem> getItems(String credentials) throws Exception {
        Map<String, Object> creds = objectMapper.readValue(credentials, new TypeReference<>() {});
        String accessToken = (String) creds.get("access_token");

        List<IntegrationItem> items = new ArrayList<>();

        // Fetch customers
        List<Map<String, Object>> customers = fetchStripeList(accessToken, "customers");
        for (Map<String, Object> customer : customers) {
            String name = (String) customer.get("name");
            String email = (String) customer.get("email");
            if (name == null || name.isBlank()) name = email;
            if (name == null || name.isBlank()) name = "Customer " + customer.get("id");
            items.add(createItemMetadata(customer, "Customer", name));
        }

        // Fetch payment intents
        List<Map<String, Object>> paymentIntents = fetchStripeList(accessToken, "payment_intents");
        for (Map<String, Object> pi : paymentIntents) {
            String description = (String) pi.get("description");
            Object amountObj = pi.get("amount");
            String currency = (String) pi.get("currency");
            String name = description != null ? description
                    : String.format("Payment %s %s", amountObj, currency != null ? currency.toUpperCase() : "");
            items.add(createItemMetadata(pi, "PaymentIntent", name.trim()));
        }

        // Fetch charges
        List<Map<String, Object>> charges = fetchStripeList(accessToken, "charges");
        for (Map<String, Object> charge : charges) {
            String description = (String) charge.get("description");
            Object amountObj = charge.get("amount");
            String currency = (String) charge.get("currency");
            String name = description != null ? description
                    : String.format("Charge %s %s", amountObj, currency != null ? currency.toUpperCase() : "");
            items.add(createItemMetadata(charge, "Charge", name.trim()));
        }

        System.out.println("list_of_integration_item_metadata: " + items);
        return items;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchStripeList(String accessToken, String resource) {
        List<Map<String, Object>> allResults = new ArrayList<>();
        String startingAfter = null;

        while (true) {
            String url = "https://api.stripe.com/v1/" + resource + "?limit=100";
            if (startingAfter != null) {
                url += "&starting_after=" + startingAfter;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, request, String.class);
                if (response.getStatusCode() != HttpStatus.OK) break;

                Map<String, Object> data = objectMapper.readValue(
                        response.getBody(), new TypeReference<>() {});
                List<Map<String, Object>> results =
                        (List<Map<String, Object>>) data.getOrDefault("data", List.of());
                allResults.addAll(results);

                Boolean hasMore = (Boolean) data.get("has_more");
                if (Boolean.TRUE.equals(hasMore) && !results.isEmpty()) {
                    startingAfter = (String) results.get(results.size() - 1).get("id");
                } else {
                    break;
                }
            } catch (Exception e) {
                break;
            }
        }

        return allResults;
    }

    private IntegrationItem createItemMetadata(Map<String, Object> json, String itemType, String name) {
        String objId = (String) json.get("id");
        Object created = json.get("created");
        String creationTime = created != null ? created.toString() : null;

        return IntegrationItem.builder()
                .id(objId + "_" + itemType)
                .type(itemType)
                .name(name)
                .creationTime(creationTime)
                .url("https://dashboard.stripe.com/" + itemType.toLowerCase() + "s/" + objId)
                .build();
    }

    @SuppressWarnings("unused")
    private List<IntegrationItem> getItemsFallback(String credentials, Exception e) {
        System.err.println("Circuit breaker open for Stripe: " + e.getMessage());
        return Collections.emptyList();
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
