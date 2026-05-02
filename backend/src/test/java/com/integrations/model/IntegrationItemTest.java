package com.integrations.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationItemTest {

    @Test
    void builder_setsAllFields() {
        IntegrationItem item = IntegrationItem.builder()
                .id("123_Contact")
                .type("Contact")
                .name("John Doe")
                .creationTime("2024-01-01T00:00:00Z")
                .lastModifiedTime("2024-06-01T00:00:00Z")
                .url("https://example.com/123")
                .parentId("parent_1")
                .parentPathOrName("Parent Name")
                .build();

        assertEquals("123_Contact", item.getId());
        assertEquals("Contact", item.getType());
        assertEquals("John Doe", item.getName());
        assertEquals("2024-01-01T00:00:00Z", item.getCreationTime());
        assertEquals("2024-06-01T00:00:00Z", item.getLastModifiedTime());
        assertEquals("https://example.com/123", item.getUrl());
        assertEquals("parent_1", item.getParentId());
        assertEquals("Parent Name", item.getParentPathOrName());
    }

    @Test
    void builder_defaultValues() {
        IntegrationItem item = IntegrationItem.builder().build();

        assertFalse(item.isDirectory());
        assertTrue(item.getVisibility());
        assertNull(item.getId());
        assertNull(item.getName());
        assertNull(item.getChildren());
    }

    @Test
    void equality_worksCorrectly() {
        IntegrationItem a = IntegrationItem.builder().id("1").name("A").build();
        IntegrationItem b = IntegrationItem.builder().id("1").name("A").build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
