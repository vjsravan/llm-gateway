package dev.jsv.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.jsv.gateway.GatewayService;

/**
 * Outbound response. Deliberately transparent about how the answer was produced —
 * a caller that cannot tell a cache hit from a fresh inference cannot reason about
 * either its latency or its bill.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
        String text,
        String servedBy,
        boolean cacheHit,
        Double cacheSimilarity,
        boolean escalated,
        String routingReason,
        int totalTokens,
        double costUsd,
        long latencyMs,
        String correlationId
) {
    public static ChatResponse from(GatewayService.Result r, String correlationId) {
        return new ChatResponse(
                r.text(),
                r.servedBy(),
                r.cacheHit(),
                r.cacheHit() ? r.cacheSimilarity() : null,
                r.escalated(),
                r.routingReason(),
                r.totalTokens(),
                r.costUsd(),
                r.latencyMs(),
                correlationId);
    }
}
