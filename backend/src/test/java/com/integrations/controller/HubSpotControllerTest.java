package com.integrations.controller;

import com.integrations.model.IntegrationItem;
import com.integrations.service.HubSpotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HubSpotController.class)
class HubSpotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HubSpotService hubSpotService;

    @Test
    void authorize_returnsAuthUrl() throws Exception {
        when(hubSpotService.authorize("user1", "org1"))
                .thenReturn("https://app.hubspot.com/oauth/authorize?client_id=test");

        mockMvc.perform(post("/integrations/hubspot/authorize")
                        .param("user_id", "user1")
                        .param("org_id", "org1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hubspot.com")));
    }

    @Test
    void authorize_missingParams_returns400() throws Exception {
        mockMvc.perform(post("/integrations/hubspot/authorize"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oauth2callback_returnsHtmlToClosePopup() throws Exception {
        when(hubSpotService.oauth2callback("code123", "{}", null, null))
                .thenReturn("<html><script>window.close();</script></html>");

        mockMvc.perform(get("/integrations/hubspot/oauth2callback")
                        .param("code", "code123")
                        .param("state", "{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("window.close()")));
    }

    @Test
    void getCredentials_returnsCredentials() throws Exception {
        when(hubSpotService.getCredentials("user1", "org1"))
                .thenReturn(Map.of("access_token", "tok_123"));

        mockMvc.perform(post("/integrations/hubspot/credentials")
                        .param("user_id", "user1")
                        .param("org_id", "org1"))
                .andExpect(status().isOk());
    }

    @Test
    void load_returnsIntegrationItems() throws Exception {
        List<IntegrationItem> items = List.of(
                IntegrationItem.builder().id("1_Contact").type("Contact").name("John Doe").build(),
                IntegrationItem.builder().id("2_Company").type("Company").name("Acme Inc").build()
        );
        when(hubSpotService.getItems("{\"access_token\":\"tok\"}")).thenReturn(items);

        mockMvc.perform(post("/integrations/hubspot/load")
                        .param("credentials", "{\"access_token\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1_Contact"))
                .andExpect(jsonPath("$[0].name").value("John Doe"))
                .andExpect(jsonPath("$[1].type").value("Company"));
    }
}
