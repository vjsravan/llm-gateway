package dev.jsv.gateway.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound completion request.
 *
 * <p>Validated at the edge so a malformed request never reaches a provider — an
 * oversized prompt rejected here costs nothing, whereas the same prompt forwarded costs
 * a full inference before the provider rejects it.
 */
public record ChatRequest(
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 32_000, message = "prompt exceeds 32000 characters")
        String prompt,

        @Min(value = 1, message = "maxTokens must be at least 1")
        @Max(value = 8_192, message = "maxTokens exceeds 8192")
        Integer maxTokens
) {
    public int maxTokensOrDefault() {
        return maxTokens == null ? 512 : maxTokens;
    }
}
