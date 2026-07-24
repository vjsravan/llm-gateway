package dev.jsv.gateway.budget;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant token budget and request rate limit.
 *
 * <p>The failure this prevents is specific and expensive: one tenant's runaway retry
 * loop consuming the shared account quota, so every other tenant starts getting 429s
 * from the provider. Without per-tenant accounting the gateway happily forwards the
 * whole storm and the blast radius is everyone.
 *
 * <p>Rate limiting uses a token bucket rather than a fixed window. A fixed window lets
 * a caller spend its entire allowance in the last second of one window and again in the
 * first second of the next — an instantaneous burst of double the intended rate,
 * arriving exactly when the provider is least able to absorb it.
 */
public class TenantBudget {

    /** Mutable per-tenant state. Guarded by synchronising on the instance. */
    private static final class Bucket {
        double tokens;
        Instant lastRefill;
        long tokensConsumed;
        long requestsAllowed;
        long requestsRejected;

        Bucket(double initial, Instant now) {
            this.tokens = initial;
            this.lastRefill = now;
        }
    }

    private final int burstCapacity;
    private final double refillPerSecond;
    private final long tokenQuota;
    private final Duration quotaWindow;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Instant> quotaWindowStart = new ConcurrentHashMap<>();

    public TenantBudget(int burstCapacity, double refillPerSecond, long tokenQuota, Duration quotaWindow) {
        this.burstCapacity = burstCapacity;
        this.refillPerSecond = refillPerSecond;
        this.tokenQuota = tokenQuota;
        this.quotaWindow = quotaWindow;
    }

    public static TenantBudget withDefaults() {
        // 20 request burst, 5 rps sustained, 200k tokens per hour.
        return new TenantBudget(20, 5.0, 200_000, Duration.ofHours(1));
    }

    public sealed interface Verdict permits Verdict.Allowed, Verdict.RateLimited, Verdict.QuotaExceeded {
        record Allowed() implements Verdict {}
        record RateLimited(double retryAfterSeconds) implements Verdict {}
        record QuotaExceeded(long used, long limit, Duration resetsIn) implements Verdict {}
    }

    public Verdict tryAcquire(String tenantId, Instant now) {
        Bucket bucket = buckets.computeIfAbsent(tenantId, k -> new Bucket(burstCapacity, now));

        synchronized (bucket) {
            // ── Token quota, checked first: exceeding it is a harder stop than rate ──
            Instant windowStart = quotaWindowStart.computeIfAbsent(tenantId, k -> now);
            if (now.isAfter(windowStart.plus(quotaWindow))) {
                quotaWindowStart.put(tenantId, now);
                bucket.tokensConsumed = 0;
            } else if (bucket.tokensConsumed >= tokenQuota) {
                bucket.requestsRejected++;
                Duration resetsIn = Duration.between(now, windowStart.plus(quotaWindow));
                return new Verdict.QuotaExceeded(bucket.tokensConsumed, tokenQuota, resetsIn);
            }

            // ── Token bucket refill ──
            double elapsedSeconds = Duration.between(bucket.lastRefill, now).toNanos() / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                bucket.tokens = Math.min(burstCapacity, bucket.tokens + elapsedSeconds * refillPerSecond);
                bucket.lastRefill = now;
            }

            if (bucket.tokens < 1.0) {
                bucket.requestsRejected++;
                double waitSeconds = (1.0 - bucket.tokens) / refillPerSecond;
                return new Verdict.RateLimited(Math.round(waitSeconds * 100) / 100.0);
            }

            bucket.tokens -= 1.0;
            bucket.requestsAllowed++;
            return new Verdict.Allowed();
        }
    }

    public Verdict tryAcquire(String tenantId) {
        return tryAcquire(tenantId, Instant.now());
    }

    /** Called after a completion so quota reflects tokens actually spent, not estimated. */
    public void recordUsage(String tenantId, int tokens) {
        Bucket bucket = buckets.get(tenantId);
        if (bucket == null) return;
        synchronized (bucket) {
            bucket.tokensConsumed += tokens;
        }
    }

    public Usage usageFor(String tenantId) {
        Bucket bucket = buckets.get(tenantId);
        if (bucket == null) return new Usage(tenantId, 0, 0, 0, tokenQuota);
        synchronized (bucket) {
            return new Usage(tenantId, bucket.tokensConsumed, bucket.requestsAllowed,
                    bucket.requestsRejected, tokenQuota);
        }
    }

    public record Usage(String tenantId, long tokensConsumed, long requestsAllowed,
                        long requestsRejected, long tokenQuota) {
        public double quotaUtilisation() {
            return tokenQuota == 0 ? 0.0 : (double) tokensConsumed / tokenQuota;
        }
    }

    public java.util.Set<String> knownTenants() {
        return buckets.keySet();
    }
}
