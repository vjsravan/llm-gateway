package dev.jsv.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end HTTP tests against the real Spring context.
 *
 * <p>Each test uses a distinct tenant id so the shared rate limiter cannot leak state
 * between tests — a suite whose outcome depends on execution order is worse than no
 * suite, because it teaches people to rerun until green.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "gateway.rate-limit-burst=3",
        "gateway.rate-limit-per-second=0.001",
        "gateway.cache-similarity-threshold=0.99"
})
class GatewayApiTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private String body(String prompt) throws Exception {
        return json.writeValueAsString(Map.of("prompt", prompt, "maxTokens", 128));
    }

    @Test
    @DisplayName("returns a completion for a valid request")
    void completesSuccessfully() throws Exception {
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-success")
                        .content(body("what is the customs status of this shipment")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").isNotEmpty())
                .andExpect(jsonPath("$.servedBy").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("one tenant's cached answer is never served to another")
    void cacheIsScopedToTheTenant() throws Exception {
        // The end-to-end form of the isolation guarantee: identical prompts, two tenants,
        // and the second must still reach a provider rather than being handed the first
        // tenant's stored response body.
        String prompt = "what is the outstanding balance on account 4471-8890";

        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-isolation-a")
                        .content(body(prompt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit").value(false));

        // Same tenant, same prompt: this one should hit, proving the cache is live.
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-isolation-a")
                        .content(body(prompt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit").value(true));

        // Different tenant, identical prompt: must be a miss.
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-isolation-b")
                        .content(body(prompt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit").value(false))
                .andExpect(jsonPath("$.servedBy").value(org.hamcrest.Matchers.not("cache")));
    }

    @Test
    @DisplayName("echoes a supplied correlation id for tracing")
    void propagatesCorrelationId() throws Exception {
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-cid")
                        .header("X-Correlation-Id", "trace-abc-123")
                        .content(body("trace this request")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").value("trace-abc-123"));
    }

    @Test
    @DisplayName("rejects a blank prompt with 400, not 500")
    void rejectsBlankPrompt() throws Exception {
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-blank")
                        .content(json.writeValueAsString(Map.of("prompt", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("rejects an out-of-range maxTokens")
    void rejectsOversizedMaxTokens() throws Exception {
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-oversized")
                        .content(json.writeValueAsString(Map.of("prompt", "hi", "maxTokens", 99_999))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 429 with Retry-After once the tenant is rate limited")
    void rateLimitsWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/v1/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", "api-throttled")
                    .content(body("distinct prompt number " + i)));
        }
        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-throttled")
                        .content(body("one request too many")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("rate_limited"));
    }

    @Test
    @DisplayName("serves a repeated prompt from cache at zero cost")
    void servesRepeatFromCache() throws Exception {
        String prompt = "explain why shipment 998877 is being held at the border";

        mvc.perform(post("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "api-cache")
                .content(body(prompt)));

        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "api-cache")
                        .content(body(prompt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit").value(true))
                .andExpect(jsonPath("$.servedBy").value("cache"))
                .andExpect(jsonPath("$.costUsd").value(0.0));
    }

    @Test
    @DisplayName("exposes stats for the dashboard")
    void exposesStats() throws Exception {
        mvc.perform(get("/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").exists())
                .andExpect(jsonPath("$.cacheHitRate").exists())
                .andExpect(jsonPath("$.costSavedUsd").exists())
                .andExpect(jsonPath("$.breakers").exists());
    }

    @Test
    @DisplayName("reports per-tenant usage")
    void reportsTenantUsage() throws Exception {
        mvc.perform(post("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "api-usage")
                .content(body("count my tokens")));

        mvc.perform(get("/v1/tenants/api-usage/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("api-usage"))
                .andExpect(jsonPath("$.requestsAllowed").value(1));
    }

    @Test
    @DisplayName("actuator health is up")
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
