package dev.jsv.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.jsv.gateway.budget.TenantBudget;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TenantBudgetTest {

    @Nested
    @DisplayName("TenantBudget")
    class Budget {

        private final Instant t0 = Instant.parse("2026-07-24T10:00:00Z");

        @Test
        @DisplayName("allows requests up to the burst capacity")
        void allowsBurst() {
            TenantBudget budget = new TenantBudget(5, 1.0, 1_000_000, Duration.ofHours(1));
            for (int i = 0; i < 5; i++) {
                assertInstanceOf(TenantBudget.Verdict.Allowed.class, budget.tryAcquire("t1", t0));
            }
        }

        @Test
        @DisplayName("rate limits once the bucket is drained")
        void rateLimitsWhenDrained() {
            TenantBudget budget = new TenantBudget(3, 1.0, 1_000_000, Duration.ofHours(1));
            for (int i = 0; i < 3; i++) budget.tryAcquire("t1", t0);

            TenantBudget.Verdict verdict = budget.tryAcquire("t1", t0);
            assertInstanceOf(TenantBudget.Verdict.RateLimited.class, verdict);
            assertTrue(((TenantBudget.Verdict.RateLimited) verdict).retryAfterSeconds() > 0);
        }

        @Test
        @DisplayName("refills over time rather than resetting in a fixed window")
        void refillsGradually() {
            // A fixed window would let a caller spend everything at the end of one window
            // and again at the start of the next — an instantaneous 2x burst.
            TenantBudget budget = new TenantBudget(5, 2.0, 1_000_000, Duration.ofHours(1));
            for (int i = 0; i < 5; i++) budget.tryAcquire("t1", t0);
            assertInstanceOf(TenantBudget.Verdict.RateLimited.class, budget.tryAcquire("t1", t0));

            assertInstanceOf(TenantBudget.Verdict.Allowed.class,
                    budget.tryAcquire("t1", t0.plusSeconds(1)), "1s at 2rps should refill 2 tokens");
        }

        @Test
        @DisplayName("isolates tenants from each other")
        void isolatesTenants() {
            // The whole point: one tenant's runaway loop must not consume everyone's quota.
            TenantBudget budget = new TenantBudget(2, 0.1, 1_000_000, Duration.ofHours(1));
            budget.tryAcquire("noisy", t0);
            budget.tryAcquire("noisy", t0);
            assertInstanceOf(TenantBudget.Verdict.RateLimited.class, budget.tryAcquire("noisy", t0));

            assertInstanceOf(TenantBudget.Verdict.Allowed.class, budget.tryAcquire("quiet", t0));
        }

        @Test
        @DisplayName("rejects once the token quota is exhausted")
        void enforcesTokenQuota() {
            TenantBudget budget = new TenantBudget(100, 100.0, 1_000, Duration.ofHours(1));
            budget.tryAcquire("t1", t0);
            budget.recordUsage("t1", 1_200);

            TenantBudget.Verdict verdict = budget.tryAcquire("t1", t0);
            assertInstanceOf(TenantBudget.Verdict.QuotaExceeded.class, verdict);
        }

        @Test
        @DisplayName("resets the quota when the window rolls over")
        void resetsQuotaWindow() {
            TenantBudget budget = new TenantBudget(100, 100.0, 1_000, Duration.ofMinutes(30));
            budget.tryAcquire("t1", t0);
            budget.recordUsage("t1", 1_200);
            assertInstanceOf(TenantBudget.Verdict.QuotaExceeded.class, budget.tryAcquire("t1", t0));

            assertInstanceOf(TenantBudget.Verdict.Allowed.class,
                    budget.tryAcquire("t1", t0.plus(Duration.ofMinutes(31))));
        }

        @Test
        @DisplayName("reports usage per tenant")
        void reportsUsage() {
            TenantBudget budget = new TenantBudget(10, 5.0, 1_000, Duration.ofHours(1));
            budget.tryAcquire("t1", t0);
            budget.recordUsage("t1", 250);

            TenantBudget.Usage usage = budget.usageFor("t1");
            assertEquals(250, usage.tokensConsumed());
            assertEquals(1, usage.requestsAllowed());
            assertEquals(0.25, usage.quotaUtilisation(), 1e-6);
        }
    }
}
