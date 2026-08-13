package dev.jsv.gateway.api;

import dev.jsv.gateway.GatewayService;
import dev.jsv.gateway.budget.TenantBudget;
import dev.jsv.gateway.cache.SemanticCache;
import dev.jsv.gateway.observability.GatewayMetrics;
import dev.jsv.gateway.resilience.CircuitBreaker;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface.
 *
 * <p>Status codes are chosen so a client can act on them without parsing the body:
 * 429 with {@code Retry-After} for rate limits, 402 for an exhausted token quota
 * (retrying will not help until the window resets), and 503 when every provider is
 * down. Returning 500 for all three, which is the common shortcut, gives the caller
 * nothing to make a decision with.
 */
@RestController
@RequestMapping("/v1")
public class GatewayController {

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "anonymous") String tenantId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = (correlationId == null || correlationId.isBlank())
                ? UUID.randomUUID().toString().substring(0, 12)
                : correlationId;

        GatewayService.Outcome outcome =
                gateway.complete(tenantId, request.prompt(), request.maxTokensOrDefault(), cid);

        return switch (outcome) {
            case GatewayService.Outcome.Success s ->
                    ResponseEntity.ok(ChatResponse.from(s.result(), cid));

            case GatewayService.Outcome.Rejected r -> {
                // 402 signals "this will not succeed until the window resets", which is a
                // different instruction to the client than "slow down". The distinction
                // comes off the enum the service already decided; re-deriving it by
                // string-matching the message would silently break on a reworded message.
                boolean isQuota = r.reason() == GatewayService.RejectionReason.TOKEN_QUOTA;
                HttpStatus status = isQuota ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.TOO_MANY_REQUESTS;
                yield ResponseEntity.status(status)
                        .header("Retry-After", String.valueOf((long) Math.ceil(r.retryAfterSeconds())))
                        .body(new ErrorResponse(
                                isQuota ? "quota_exceeded" : "rate_limited",
                                r.detail(), r.retryAfterSeconds(), cid));
            }

            case GatewayService.Outcome.Failed f ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(new ErrorResponse("upstream_unavailable", f.reason(), null, cid));
        };
    }

    /** Everything the dashboard needs in one call. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        GatewayMetrics.Snapshot m = gateway.metrics().snapshot();
        SemanticCache.Stats c = gateway.cache().stats();

        Map<String, Object> breakers = new LinkedHashMap<>();
        gateway.router().providers().forEach(p -> {
            CircuitBreaker cb = gateway.router().breakerFor(p.name());
            breakers.put(p.name(), Map.of(
                    "state", cb.state().name(),
                    "failureRate", Math.round(cb.currentFailureRate() * 1000) / 1000.0,
                    "rejectedCalls", cb.rejectedCalls(),
                    "tier", p.tier(),
                    "costPer1kTokens", p.costPer1kTokens()));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", m.requests());
        out.put("cacheHits", m.cacheHits());
        out.put("cacheHitRate", Math.round(m.cacheHitRate() * 1000) / 1000.0);
        out.put("meanHitSimilarity", Math.round(c.meanHitSimilarity() * 10000) / 10000.0);
        out.put("cacheSize", c.size());
        out.put("cacheEvictions", c.evictions());
        out.put("escalations", m.escalations());
        out.put("failures", m.failures());
        out.put("rateLimited", m.rateLimited());
        out.put("quotaExceeded", m.quotaExceeded());
        out.put("actualCostUsd", m.actualCost());
        out.put("counterfactualCostUsd", m.counterfactualCost());
        out.put("costSavedUsd", m.costSaved());
        out.put("costSavedRatio", m.costSavedRatio());
        out.put("p50LatencyMs", m.p50LatencyMs());
        out.put("p95LatencyMs", m.p95LatencyMs());
        out.put("p99LatencyMs", m.p99LatencyMs());
        out.put("requestsByProvider", m.requestsByProvider());
        out.put("breakers", breakers);
        return out;
    }

    @GetMapping("/tenants/{tenantId}/usage")
    public TenantBudget.Usage tenantUsage(
            @org.springframework.web.bind.annotation.PathVariable String tenantId) {
        return gateway.budget().usageFor(tenantId);
    }
}
