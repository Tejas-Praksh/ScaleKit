package com.scalekit.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApiResponse")
class ApiResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("success() sets correct fields")
    void success_setsCorrectFields() {
        ApiResponse<String> response = ApiResponse.success("data", "OK");

        assertTrue(response.isSuccess());
        assertEquals("OK", response.getMessage());
        assertEquals("data", response.getData());
        assertNotNull(response.getTimestamp());
        assertNull(response.getErrorCode());
    }

    @Test
    @DisplayName("error() sets correct fields")
    void error_setsCorrectFields() {
        ApiResponse<Void> response = ApiResponse.error("Something broke", "INTERNAL_ERROR");

        assertFalse(response.isSuccess());
        assertEquals("Something broke", response.getMessage());
        assertEquals("INTERNAL_ERROR", response.getErrorCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("JSON serialization hides null fields")
    void jsonSerialization_hidesNullFields() throws Exception {
        ApiResponse<String> response = ApiResponse.success("test", "OK");
        String json = mapper.writeValueAsString(response);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("success"));
        assertTrue(node.has("data"));
        assertTrue(node.has("timestamp"));
        assertFalse(node.has("errorCode"), "Null errorCode should not appear in JSON");
        assertFalse(node.has("requestId"), "Null requestId should not appear in JSON");
    }

    @Test
    @DisplayName("executionTime included when provided")
    void executionTime_included_whenProvided() {
        ApiResponse<String> response = ApiResponse.success("data", "OK", 42L);

        assertEquals(42L, response.getExecutionTimeMs());
    }

    @Test
    @DisplayName("error with details includes detail data")
    void error_withDetails_includesData() {
        ApiResponse<Object> response = ApiResponse.error("Bad input", "VALIDATION_ERROR", "field was null");

        assertFalse(response.isSuccess());
        assertEquals("field was null", response.getData());
    }
}
