package io.github.djyking.harness.client;

import com.fasterxml.jackson.databind.JsonNode;

/** JSON follows the published OpenAPI contract. ETags are opaque and must be supplied explicitly. */
public record ApiResponse(
    int status, JsonNode body, String etag, String requestId, String location, String retryAfter) {}
