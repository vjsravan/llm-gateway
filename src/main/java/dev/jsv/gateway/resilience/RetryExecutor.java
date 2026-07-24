package dev.jsv.gateway.resilience;

import dev.jsv.gateway.provider.ProviderException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Bounded retry with exponential backoff and full jitter.
 *
 * <p>Jitter is not decoration. Without it, every client that failed during the same
 * upstream blip retries at the same instant, and the recovering provider is hit by a
 * synchronised thundering herd — so the retry storm causes the second outage. Full
 * jitter (sleep uniformly in {@code [0, backoff]}) spreads that load flat and is the
 * variant AWS's architecture guidance recommends.
 *
 * <p>Non-retryable failures return immediately. Retrying a 400 wastes budget and delays
 * the error the caller needs to see.
 */
public class RetryExecutor {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryExecutor(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public static RetryExecutor withDefaults() {
        return new RetryExecutor(3, Duration.ofMillis(120), Duration.ofSeconds(4));
    }

    @FunctionalInterface
    public interface Call<T> {
        T run() throws ProviderException;
    }

    /**
     * Runs {@code call}, retrying retryable failures up to {@code maxAttempts} times.
     *
     * @param onRetry invoked before each sleep, for metrics and logging
     * @throws ProviderException the last failure, once attempts are exhausted
     */
    public <T> T execute(Call<T> call, RetryListener onRetry) throws ProviderException {
        ProviderException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.run();
            } catch (ProviderException e) {
                last = e;
                if (!e.isRetryable() || attempt == maxAttempts) {
                    throw e;
                }
                long sleepMs = fullJitterBackoffMs(attempt);
                if (onRetry != null) {
                    onRetry.onRetry(attempt, sleepMs, e);
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException(e.getProvider(), "interrupted during backoff", false, ie);
                }
            }
        }
        throw last;
    }

    public <T> T execute(Call<T> call) throws ProviderException {
        return execute(call, null);
    }

    /**
     * Exponential ceiling, then a uniform draw below it.
     *
     * <p>Public so the backoff policy can be asserted directly — the jitter is the part
     * most likely to be quietly removed by a later "simplification", and a test that
     * cannot see it cannot protect it.
     */
    public long fullJitterBackoffMs(int attempt) {
        long exponential = (long) (baseDelay.toMillis() * Math.pow(2, attempt - 1L));
        long ceiling = Math.min(exponential, maxDelay.toMillis());
        return ceiling <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    @FunctionalInterface
    public interface RetryListener {
        void onRetry(int attempt, long sleepMs, ProviderException cause);
    }

    /** Convenience for suppliers that cannot throw. */
    public <T> T executeUnchecked(Supplier<T> supplier) {
        try {
            return execute(supplier::get);
        } catch (ProviderException e) {
            throw new IllegalStateException("unreachable: supplier cannot throw ProviderException", e);
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
