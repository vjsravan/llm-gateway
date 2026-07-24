package dev.jsv.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.jsv.gateway.provider.ProviderException;
import dev.jsv.gateway.resilience.CircuitBreaker;
import dev.jsv.gateway.resilience.RetryExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The behaviours these cover are the ones that only show up under load or during an
 * incident, which is exactly why they need tests rather than a manual check.
 */
class ResilienceTest {

    @Nested
    @DisplayName("CircuitBreaker")
    class Breaker {

        @Test
        @DisplayName("stays closed below the minimum call count even at 100% failure")
        void doesNotOpenOnTinySample() {
            // Without a minimum-calls floor, the first failure of the day sits at a 100%
            // failure rate and takes a healthy provider out of rotation.
            CircuitBreaker cb = new CircuitBreaker("t", 20, 0.5, 8, Duration.ofSeconds(10), 3);
            for (int i = 0; i < 7; i++) {
                cb.recordFailure();
            }
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
            assertTrue(cb.allowRequest());
        }

        @Test
        @DisplayName("opens once the failure rate crosses the threshold on a real sample")
        void opensOnSustainedFailure() {
            CircuitBreaker cb = new CircuitBreaker("t", 20, 0.5, 8, Duration.ofSeconds(10), 3);
            for (int i = 0; i < 10; i++) {
                cb.recordFailure();
            }
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
            assertFalse(cb.allowRequest());
        }

        @Test
        @DisplayName("rejects without calling the provider while open")
        void rejectsWhileOpen() {
            CircuitBreaker cb = new CircuitBreaker("t", 10, 0.5, 4, Duration.ofSeconds(30), 2);
            for (int i = 0; i < 6; i++) cb.recordFailure();

            for (int i = 0; i < 5; i++) assertFalse(cb.allowRequest());
            assertEquals(5, cb.rejectedCalls());
        }

        @Test
        @DisplayName("moves to half-open after the cool-down elapses")
        void halfOpensAfterCooldown() {
            CircuitBreaker cb = new CircuitBreaker("t", 10, 0.5, 4, Duration.ofSeconds(10), 2);
            Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
            for (int i = 0; i < 6; i++) cb.recordFailure(t0);

            assertFalse(cb.allowRequest(t0.plusSeconds(5)), "still cooling down");
            assertTrue(cb.allowRequest(t0.plusSeconds(11)), "cool-down elapsed");
            assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        }

        @Test
        @DisplayName("admits only the probe budget while half-open")
        void limitsHalfOpenProbes() {
            // Dumping full traffic at a provider that just came back is how the second,
            // longer outage happens.
            CircuitBreaker cb = new CircuitBreaker("t", 10, 0.5, 4, Duration.ofSeconds(10), 3);
            Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
            for (int i = 0; i < 6; i++) cb.recordFailure(t0);

            Instant after = t0.plusSeconds(11);
            assertTrue(cb.allowRequest(after));   // probe 1
            assertTrue(cb.allowRequest(after));   // probe 2
            assertTrue(cb.allowRequest(after));   // probe 3
            assertFalse(cb.allowRequest(after), "probe budget exhausted");
        }

        @Test
        @DisplayName("closes only after the full probe budget succeeds")
        void closesAfterSuccessfulProbes() {
            CircuitBreaker cb = new CircuitBreaker("t", 10, 0.5, 4, Duration.ofSeconds(10), 3);
            Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
            for (int i = 0; i < 6; i++) cb.recordFailure(t0);
            Instant after = t0.plusSeconds(11);
            cb.allowRequest(after);

            cb.recordSuccess(after);
            assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state(), "one probe is not proof");
            cb.recordSuccess(after);
            cb.recordSuccess(after);
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        }

        @Test
        @DisplayName("reopens immediately on a failed probe")
        void reopensOnFailedProbe() {
            CircuitBreaker cb = new CircuitBreaker("t", 10, 0.5, 4, Duration.ofSeconds(10), 3);
            Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
            for (int i = 0; i < 6; i++) cb.recordFailure(t0);
            Instant after = t0.plusSeconds(11);
            cb.allowRequest(after);

            cb.recordFailure(after);
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
        }

        @Test
        @DisplayName("recovers fully after a success streak")
        void recoversAfterSuccess() {
            CircuitBreaker cb = new CircuitBreaker("t", 10, 0.5, 4, Duration.ofSeconds(1), 1);
            Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
            for (int i = 0; i < 6; i++) cb.recordFailure(t0);
            Instant after = t0.plusSeconds(2);
            cb.allowRequest(after);
            cb.recordSuccess(after);

            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
            assertEquals(0.0, cb.currentFailureRate(), "window resets on close");
        }

        @Test
        @DisplayName("rejects an invalid threshold")
        void validatesThreshold() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CircuitBreaker("t", 10, 1.5, 4, Duration.ofSeconds(1), 1));
        }
    }

    @Nested
    @DisplayName("RetryExecutor")
    class Retry {

        @Test
        @DisplayName("returns immediately on success without retrying")
        void succeedsFirstTime() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            RetryExecutor retry = RetryExecutor.withDefaults();
            String out = retry.execute(() -> {
                calls.incrementAndGet();
                return "ok";
            });
            assertEquals("ok", out);
            assertEquals(1, calls.get());
        }

        @Test
        @DisplayName("retries retryable failures then succeeds")
        void retriesUntilSuccess() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            RetryExecutor retry = new RetryExecutor(4, Duration.ofMillis(1), Duration.ofMillis(4));
            String out = retry.execute(() -> {
                if (calls.incrementAndGet() < 3) {
                    throw new ProviderException("p", "503", true);
                }
                return "ok";
            });
            assertEquals("ok", out);
            assertEquals(3, calls.get());
        }

        @Test
        @DisplayName("does not retry a permanent failure")
        void doesNotRetryPermanentFailures() {
            // Retrying a 400 burns budget and delays the error the caller needs.
            AtomicInteger calls = new AtomicInteger();
            RetryExecutor retry = new RetryExecutor(5, Duration.ofMillis(1), Duration.ofMillis(4));
            assertThrows(ProviderException.class, () -> retry.execute(() -> {
                calls.incrementAndGet();
                throw new ProviderException("p", "400 bad prompt", false);
            }));
            assertEquals(1, calls.get(), "a permanent failure must be attempted once");
        }

        @Test
        @DisplayName("gives up after maxAttempts and rethrows the last failure")
        void exhaustsAttempts() {
            AtomicInteger calls = new AtomicInteger();
            RetryExecutor retry = new RetryExecutor(3, Duration.ofMillis(1), Duration.ofMillis(2));
            ProviderException thrown = assertThrows(ProviderException.class, () -> retry.execute(() -> {
                calls.incrementAndGet();
                throw new ProviderException("p", "still down", true);
            }));
            assertEquals(3, calls.get());
            assertEquals("still down", thrown.getMessage());
        }

        @Test
        @DisplayName("backoff grows exponentially and is capped")
        void backoffIsBoundedAndGrows() {
            RetryExecutor retry = new RetryExecutor(10, Duration.ofMillis(100), Duration.ofMillis(1000));
            for (int i = 0; i < 200; i++) {
                assertTrue(retry.fullJitterBackoffMs(1) <= 100);
                assertTrue(retry.fullJitterBackoffMs(3) <= 400);
                assertTrue(retry.fullJitterBackoffMs(9) <= 1000, "must respect the cap");
            }
        }

        @Test
        @DisplayName("jitter actually varies so clients do not retry in lockstep")
        void jitterIsRandomised() {
            // Without jitter every client that failed during the same blip retries at the
            // same instant, and the herd causes the second outage.
            RetryExecutor retry = new RetryExecutor(5, Duration.ofMillis(500), Duration.ofSeconds(4));
            long first = retry.fullJitterBackoffMs(3);
            boolean sawDifferent = false;
            for (int i = 0; i < 100 && !sawDifferent; i++) {
                if (retry.fullJitterBackoffMs(3) != first) sawDifferent = true;
            }
            assertTrue(sawDifferent, "backoff must not be a constant");
        }

        @Test
        @DisplayName("notifies the listener before each retry")
        void invokesRetryListener() {
            AtomicInteger notifications = new AtomicInteger();
            RetryExecutor retry = new RetryExecutor(3, Duration.ofMillis(1), Duration.ofMillis(2));
            assertThrows(ProviderException.class, () -> retry.execute(
                    () -> { throw new ProviderException("p", "down", true); },
                    (attempt, sleepMs, cause) -> notifications.incrementAndGet()));
            assertEquals(2, notifications.get(), "3 attempts means 2 inter-attempt waits");
        }
    }
}
