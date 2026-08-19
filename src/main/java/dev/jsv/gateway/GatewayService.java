package dev.jsv.gateway;

import dev.jsv.gateway.budget.TenantBudget;
import dev.jsv.gateway.cache.SemanticCache;
import dev.jsv.gateway.observability.GatewayMetrics;
import dev.jsv.gateway.provider.Completion;
import dev.jsv.gateway.provider.LlmProvider;
import dev.jsv.gateway.provider.ProviderException;
import dev.jsv.gateway.routing.ModelRouter;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Composes the gateway pipeline in the order that costs the least:
 *
 * <pre>
 *   budget check  →  semantic cache  →  routed provider call  →  cache write
 * </pre>
 *
 * <p>The ordering is the whole point. Budget rejection is free, so it goes first — no
 * sense embedding a prompt for a tenant who is over quota. The cache runs before any
 * provider call because a hit costs one embedding instead of one inference. Routing
 * comes last because it is the only step that spends real money.
 *
 * <p>Every request carries a correlation id through {@link MDC} so a single completion
 * can be traced across the cache decision, the routing path, and any retries — the same
 * discipline as tracing a message through a queue topology, applied to inference.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final ModelRouter router;
    private final SemanticCache cache;
    private final TenantBudget budget;
    private final GatewayMetrics metrics;
    private final double premiumCostPer1k;

    public GatewayService(ModelRouter router, SemanticCache cache,
                          TenantBudget budget, GatewayMetrics metrics) {
        this.router = router;
        this.cache = cache;
        this.budget = budget;
        this.metrics = metrics;
        this.premiumCostPer1k = router.providers().stream()
                .max(Comparator.comparingDouble(LlmProvider::costPer1kTokens))
                .map(LlmProvider::costPer1kTokens)
                .orElse(1.0);
    }

    /** What happened, in enough detail for the caller to understand the bill. */
    public record Result(
            String text,
            String servedBy,
            boolean cacheHit,
            double cacheSimilarity,
            boolean escalated,
            String routingReason,
            int totalTokens,
            double costUsd,
            long latencyMs
    ) {}

    /** Why a request was turned away. Carried as a type, not sniffed back out of a
     *  message string — the distinction is what tells the caller whether retrying can
     *  ever help, so it has to survive the trip to the controller. */
    public enum RejectionReason { RATE_LIMIT, TOKEN_QUOTA }

    public sealed interface Outcome permits Outcome.Success, Outcome.Rejected, Outcome.Failed {
        record Success(Result result) implements Outcome {}
        record Rejected(RejectionReason reason, String detail, double retryAfterSeconds) implements Outcome {}
        record Failed(String reason) implements Outcome {}
    }

    public Outcome complete(String tenantId, String prompt, int maxTokens, String correlationId) {
        MDC.put("correlationId", correlationId);
        MDC.put("tenant", tenantId);
        long started = System.nanoTime();

        try {
            // ── 1. Budget: cheapest possible rejection ──
            TenantBudget.Verdict verdict = budget.tryAcquire(tenantId);
            if (verdict instanceof TenantBudget.Verdict.RateLimited limited) {
                metrics.recordRateLimited();
                log.info("rate limited, retry after {}s", limited.retryAfterSeconds());
                return new Outcome.Rejected(RejectionReason.RATE_LIMIT,
                        "rate limit exceeded", limited.retryAfterSeconds());
            }
            if (verdict instanceof TenantBudget.Verdict.QuotaExceeded quota) {
                metrics.recordQuotaExceeded();
                log.info("quota exceeded: {}/{} tokens", quota.used(), quota.limit());
                return new Outcome.Rejected(RejectionReason.TOKEN_QUOTA,
                        "token quota exceeded (%d/%d)".formatted(quota.used(), quota.limit()),
                        quota.resetsIn().toSeconds());
            }

            // ── 2. Semantic cache, scoped to this tenant ──
            Optional<SemanticCache.Hit> hit = cache.lookup(tenantId, prompt, maxTokens);
            if (hit.isPresent()) {
                SemanticCache.Hit h = hit.get();
                long latencyMs = elapsedMs(started);
                metrics.recordCacheHit(latencyMs);

                double wouldHaveCost = costOf(h.completion().totalTokens(), premiumCostPer1k);
                metrics.recordCost(0.0, wouldHaveCost);

                log.info("cache hit similarity={} matched={}",
                        "%.4f".formatted(h.similarity()), abbreviate(h.matchedPrompt()));

                return new Outcome.Success(new Result(
                        h.completion().text(), "cache", true, round4(h.similarity()),
                        false, "served from semantic cache", h.completion().totalTokens(),
                        0.0, latencyMs));
            }

            // ── 3. Routed provider call ──
            ModelRouter.Decision decision = router.route(prompt, maxTokens);
            Completion completion = decision.completion();
            long latencyMs = elapsedMs(started);

            if (decision.escalated()) {
                metrics.recordEscalation();
            }
            metrics.recordRequest(latencyMs, completion.providerName());
            budget.recordUsage(tenantId, completion.totalTokens());

            double spent = costOf(completion.totalTokens(), costPer1kFor(completion.providerName()));
            double wouldHaveCost = costOf(completion.totalTokens(), premiumCostPer1k);
            metrics.recordCost(spent, wouldHaveCost);

            // ── 4. Populate this tenant's cache for the next equivalent prompt ──
            cache.put(tenantId, prompt, completion);

            log.info("served by {} escalated={} tokens={} cost={} latency={}ms",
                    completion.providerName(), decision.escalated(),
                    completion.totalTokens(), "%.5f".formatted(spent), latencyMs);

            return new Outcome.Success(new Result(
                    completion.text(), completion.providerName(), false, 0.0,
                    decision.escalated(), decision.reason(), completion.totalTokens(),
                    round4(spent), latencyMs));

        } catch (ProviderException e) {
            metrics.recordFailure();
            log.error("all providers unavailable: {}", e.getMessage());
            return new Outcome.Failed(e.getMessage());
        } finally {
            MDC.clear();
        }
    }

    private double costPer1kFor(String providerName) {
        return router.providers().stream()
                .filter(p -> p.name().equals(providerName))
                .findFirst()
                .map(LlmProvider::costPer1kTokens)
                .orElse(premiumCostPer1k);
    }

    private static double costOf(int tokens, double costPer1k) {
        return tokens / 1000.0 * costPer1k;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    public GatewayMetrics metrics() {
        return metrics;
    }

    public SemanticCache cache() {
        return cache;
    }

    public ModelRouter router() {
        return router;
    }

    public TenantBudget budget() {
        return budget;
    }
}
