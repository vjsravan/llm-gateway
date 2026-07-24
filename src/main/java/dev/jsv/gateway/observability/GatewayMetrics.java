package dev.jsv.gateway.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Gateway counters and a latency histogram.
 *
 * <p>Latency is kept as a bucketed histogram rather than a running mean because the
 * mean is the least useful statistic here: a p50 of 200ms with a p99 of 9s is a
 * completely different system from a flat 300ms, and averaging hides exactly the tail
 * users complain about.
 *
 * <p>Cost-saved is tracked as counterfactual spend: what the traffic *would* have cost
 * had every request gone to the most expensive tier with no cache. That framing is the
 * one worth reporting, because "we spent $40" means nothing without "and would have
 * spent $260."
 */
public class GatewayMetrics {

    // Bucket ceilings in milliseconds; last bucket is the overflow.
    private static final long[] BUCKET_BOUNDS_MS = {10, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400, Long.MAX_VALUE};

    private final AtomicLong[] latencyBuckets = new AtomicLong[BUCKET_BOUNDS_MS.length];
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong escalations = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong quotaExceeded = new AtomicLong();

    private final DoubleAdder actualCost = new DoubleAdder();
    private final DoubleAdder counterfactualCost = new DoubleAdder();

    private final Map<String, AtomicLong> perProvider = new ConcurrentHashMap<>();

    public GatewayMetrics() {
        for (int i = 0; i < latencyBuckets.length; i++) {
            latencyBuckets[i] = new AtomicLong();
        }
    }

    public void recordRequest(long latencyMs, String provider) {
        requests.incrementAndGet();
        perProvider.computeIfAbsent(provider, k -> new AtomicLong()).incrementAndGet();
        for (int i = 0; i < BUCKET_BOUNDS_MS.length; i++) {
            if (latencyMs <= BUCKET_BOUNDS_MS[i]) {
                latencyBuckets[i].incrementAndGet();
                return;
            }
        }
    }

    public void recordCacheHit(long latencyMs) {
        cacheHits.incrementAndGet();
        recordRequest(latencyMs, "cache");
    }

    public void recordEscalation() {
        escalations.incrementAndGet();
    }

    public void recordFailure() {
        failures.incrementAndGet();
    }

    public void recordRateLimited() {
        rateLimited.incrementAndGet();
    }

    public void recordQuotaExceeded() {
        quotaExceeded.incrementAndGet();
    }

    /**
     * @param spent      what this request actually cost
     * @param ifPremium  what it would have cost on the top tier with no cache
     */
    public void recordCost(double spent, double ifPremium) {
        actualCost.add(spent);
        counterfactualCost.add(ifPremium);
    }

    /** Approximate percentile from the histogram. Reports the bucket ceiling. */
    public long percentileMs(double p) {
        long total = requests.get();
        if (total == 0) return 0;
        long target = (long) Math.ceil(p * total);
        long cumulative = 0;
        for (int i = 0; i < latencyBuckets.length; i++) {
            cumulative += latencyBuckets[i].get();
            if (cumulative >= target) {
                return BUCKET_BOUNDS_MS[i] == Long.MAX_VALUE ? 6400 : BUCKET_BOUNDS_MS[i];
            }
        }
        return 6400;
    }

    public Snapshot snapshot() {
        long total = requests.get();
        double actual = actualCost.sum();
        double counterfactual = counterfactualCost.sum();
        return new Snapshot(
                total,
                cacheHits.get(),
                total == 0 ? 0.0 : (double) cacheHits.get() / total,
                escalations.get(),
                failures.get(),
                rateLimited.get(),
                quotaExceeded.get(),
                round(actual),
                round(counterfactual),
                round(counterfactual - actual),
                counterfactual == 0 ? 0.0 : round((counterfactual - actual) / counterfactual),
                percentileMs(0.50),
                percentileMs(0.95),
                percentileMs(0.99),
                Map.copyOf(perProvider.entrySet().stream()
                        .collect(ConcurrentHashMap::new,
                                (m, e) -> m.put(e.getKey(), e.getValue().get()),
                                ConcurrentHashMap::putAll))
        );
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public record Snapshot(
            long requests,
            long cacheHits,
            double cacheHitRate,
            long escalations,
            long failures,
            long rateLimited,
            long quotaExceeded,
            double actualCost,
            double counterfactualCost,
            double costSaved,
            double costSavedRatio,
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs,
            Map<String, Long> requestsByProvider
    ) {}
}
