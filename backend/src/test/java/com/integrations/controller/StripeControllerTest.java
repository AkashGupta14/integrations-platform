package com.integrations.controller;

import com.integrations.model.IntegrationItem;
import com.integrations.service.StripeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StripeController.class)
class StripeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StripeService stripeService;

    @Test
    void authorize_returnsStripeConnectUrl() throws Exception {
        when(stripeService.authorize("user1", "org1"))
                .thenReturn("https://connect.stripe.com/oauth/authorize?client_id=ca_test");

        mockMvc.perform(post("/integrations/stripe/authorize")
                        .param("user_id", "user1")
                        .param("org_id", "org1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stripe.com")));
    }

    @Test
    void load_returnsCustomersAndPayments() throws Exception {
        List<IntegrationItem> items = List.of(
                IntegrationItem.builder().id("cus_1_Customer").type("Customer").name("Jane Doe").build(),
                IntegrationItem.builder().id("pi_1_PaymentIntent").type("PaymentIntent").name("Invoice #42").build()
        );
        when(stripeService.getItems("{\"access_token\":\"sk_test\"}")).thenReturn(items);

        mockMvc.perform(post("/integrations/stripe/load")
                        .param("credentials", "{\"access_token\":\"sk_test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("Customer"))
                .andExpect(jsonPath("$[1].type").value("PaymentIntent"));
    }
}
