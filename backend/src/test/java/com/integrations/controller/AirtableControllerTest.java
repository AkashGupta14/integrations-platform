package com.integrations.controller;

import com.integrations.model.IntegrationItem;
import com.integrations.service.AirtableService;
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

@WebMvcTest(AirtableController.class)
class AirtableControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AirtableService airtableService;

    @Test
    void authorize_returnsAuthUrl() throws Exception {
        when(airtableService.authorize("u1", "o1"))
                .thenReturn("https://airtable.com/oauth2/v1/authorize?client_id=test");

        mockMvc.perform(post("/integrations/airtable/authorize")
                        .param("user_id", "u1")
                        .param("org_id", "o1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("airtable.com")));
    }

    @Test
    void load_returnsBasesAndTables() throws Exception {
        List<IntegrationItem> items = List.of(
                IntegrationItem.builder().id("app1_Base").type("Base").name("My Base").build(),
                IntegrationItem.builder().id("tbl1_Table").type("Table").name("Tasks").parentId("app1_Base").build()
        );
        when(airtableService.getItems("{\"access_token\":\"tok\"}")).thenReturn(items);

        mockMvc.perform(post("/integrations/airtable/load")
                        .param("credentials", "{\"access_token\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("Base"))
                .andExpect(jsonPath("$[1].parent_id").value("app1_Base"));
    }
}
