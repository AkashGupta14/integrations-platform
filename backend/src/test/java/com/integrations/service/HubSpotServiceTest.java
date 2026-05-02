package com.integrations.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrations.model.IntegrationItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HubSpotServiceTest {

    @Mock
    private RedisService redisService;

    @Mock
    private RestTemplate restTemplate;

    private HubSpotService hubSpotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        hubSpotService = new HubSpotService(redisService, restTemplate, objectMapper);
        ReflectionTestUtils.setField(hubSpotService, "clientId", "test-client-id");
        ReflectionTestUtils.setField(hubSpotService, "clientSecret", "test-client-secret");
    }

    @Test
    void authorize_storesStateInRedisAndReturnsUrl() throws Exception {
        String url = hubSpotService.authorize("user1", "org1");

        assertTrue(url.startsWith("https://app.hubspot.com/oauth/authorize"));
        assertTrue(url.contains("client_id=test-client-id"));
        verify(redisService).set(eq("hubspot_state:org1:user1"), anyString(), eq(600));
    }

    @Test
    void getCredentials_throwsWhenNotFound() {
        when(redisService.get("hubspot_credentials:org1:user1")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> hubSpotService.getCredentials("user1", "org1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void getCredentials_deletesAfterReading() throws Exception {
        when(redisService.get("hubspot_credentials:org1:user1"))
                .thenReturn("{\"access_token\":\"tok_123\"}");

        Object result = hubSpotService.getCredentials("user1", "org1");
        assertNotNull(result);
        verify(redisService).delete("hubspot_credentials:org1:user1");
    }

    @Test
    void getItems_parsesContactsCompaniesDeals() throws Exception {
        String contactsResponse = "{\"results\":[{\"id\":\"101\",\"properties\":{\"firstname\":\"John\",\"lastname\":\"Doe\",\"email\":\"j@test.com\",\"createdate\":\"2024-01-01\"}}]}";
        String companiesResponse = "{\"results\":[{\"id\":\"201\",\"properties\":{\"name\":\"Acme\",\"createdate\":\"2024-01-01\",\"hs_lastmodifieddate\":\"2024-06-01\"}}]}";
        String dealsResponse = "{\"results\":[{\"id\":\"301\",\"properties\":{\"dealname\":\"Big Deal\",\"createdate\":\"2024-02-01\",\"hs_lastmodifieddate\":\"2024-06-01\"}}]}";

        when(restTemplate.exchange(contains("/contacts"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(contactsResponse, HttpStatus.OK));
        when(restTemplate.exchange(contains("/companies"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(companiesResponse, HttpStatus.OK));
        when(restTemplate.exchange(contains("/deals"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(dealsResponse, HttpStatus.OK));

        List<IntegrationItem> items = hubSpotService.getItems("{\"access_token\":\"tok\"}");

        assertEquals(3, items.size());
        assertEquals("John Doe", items.get(0).getName());
        assertEquals("Contact", items.get(0).getType());
        assertEquals("Acme", items.get(1).getName());
        assertEquals("Company", items.get(1).getType());
        assertEquals("Big Deal", items.get(2).getName());
        assertEquals("Deal", items.get(2).getType());
    }

    @Test
    void getItems_fallsBackToEmailWhenNameBlank() throws Exception {
        String contactsResponse = "{\"results\":[{\"id\":\"101\",\"properties\":{\"firstname\":\"\",\"lastname\":\"\",\"email\":\"fallback@test.com\",\"createdate\":\"2024-01-01\"}}]}";
        String empty = "{\"results\":[]}";

        when(restTemplate.exchange(contains("/contacts"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(contactsResponse, HttpStatus.OK));
        when(restTemplate.exchange(contains("/companies"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(empty, HttpStatus.OK));
        when(restTemplate.exchange(contains("/deals"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(empty, HttpStatus.OK));

        List<IntegrationItem> items = hubSpotService.getItems("{\"access_token\":\"tok\"}");

        assertEquals(1, items.size());
        assertEquals("fallback@test.com", items.get(0).getName());
    }
}
