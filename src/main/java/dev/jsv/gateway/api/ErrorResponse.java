package dev.jsv.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error payload. {@code retryAfterSeconds} is present when retrying could succeed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String message,
        Double retryAfterSeconds,
        String correlationId
) {}
