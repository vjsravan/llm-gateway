package dev.jsv.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.jsv.gateway.cache.Embedder;
import dev.jsv.gateway.cache.HashingEmbedder;
import dev.jsv.gateway.cache.SemanticCache;
import dev.jsv.gateway.provider.Completion;
import dev.jsv.gateway.provider.LlmProvider;
import dev.jsv.gateway.provider.ProviderException;
import dev.jsv.gateway.resilience.CircuitBreaker;
import dev.jsv.gateway.resilience.RetryExecutor;
import dev.jsv.gateway.routing.ModelRouter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CacheAndRoutingTest {

    /** A provider with fully controlled behaviour, so routing logic is tested in isolation. */
    private static final class StubProvider implements LlmProvider {
        private final String name;
        private final int tier;
        private final double cost;
        private final double confidence;
        private final ProviderException failWith;
        final AtomicInteger calls = new AtomicInteger();

        StubProvider(String name, int tier, double cost, double confidence, ProviderException failWith) {
            this.name = name;
            this.tier = tier;
            this.cost = cost;
            this.confidence = confidence;
            this.failWith = failWith;
        }

        static StubProvider ok(String name, int tier, double cost, double confidence) {
            return new StubProvider(name, tier, cost, confidence, null);
        }

        static StubProvider failing(String name, int tier, boolean retryable) {
            return new StubProvider(name, tier, 1.0, 0.0,
                    new ProviderException(name, "down", retryable));
        }

        public String name() { return name; }
        public double costPer1kTokens() { return cost; }
        public int tier() { return tier; }

        public Completion complete(String prompt, int maxTokens) throws ProviderException {
            calls.incrementAndGet();
            if (failWith != null) throw failWith;
            return new Completion("answer from " + name, 10, 20, confidence, name);
        }
    }

    /**
     * Fails the first {@code failuresPerCall} attempts of every logical call, then
     * succeeds — the degraded-but-recovering shape a retry loop hides from the breaker.
     */
    private static final class FlakyProvider implements LlmProvider {
        private final String name;
        private final int tier;
        private final int failuresPerCall;
        private int consecutiveFailures = 0;
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        FlakyProvider(String name, int tier, int failuresPerCall) {
            this.name = name;
            this.tier = tier;
            this.failuresPerCall = failuresPerCall;
        }

        public String name() { return name; }
        public double costPer1kTokens() { return 0.25; }
        public int tier() { return tier; }

        public Completion complete(String prompt, int maxTokens) throws ProviderException {
            if (consecutiveFailures < failuresPerCall) {
                consecutiveFailures++;
                failures.incrementAndGet();
                throw new ProviderException(name, "transient 503", true);
            }
            consecutiveFailures = 0;
            successes.incrementAndGet();
            return new Completion("answer from " + name, 10, 20, 0.99, name);
        }
    }

    private static Completion sample() {
        return new Completion("cached answer", 10, 20, 0.9, "stub");
    }

    /** Larger than any sample()'s output, so maxTokens never accidentally filters a test. */
    private static final int ANY_TOKENS = 4096;

    private static final String TENANT = "acme";

    @Nested
    @DisplayName("SemanticCache")
    class Cache {

        @Test
        @DisplayName("returns a miss on an empty cache")
        void missesWhenEmpty() {
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            assertTrue(cache.lookup(TENANT, "anything", ANY_TOKENS).isEmpty());
            assertEquals(0.0, cache.stats().hitRate());
        }

        @Test
        @DisplayName("hits on an identical prompt")
        void hitsOnExactMatch() {
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            cache.put(TENANT, "what is the status of AWB 125", sample());

            Optional<SemanticCache.Hit> hit =
                    cache.lookup(TENANT, "what is the status of AWB 125", ANY_TOKENS);
            assertTrue(hit.isPresent());
            assertEquals(1.0, hit.get().similarity(), 1e-6);
        }

        @Test
        @DisplayName("does not hit on an unrelated prompt")
        void missesOnUnrelatedPrompt() {
            // The dangerous failure is a false hit: a confidently wrong answer to a
            // question nobody asked, nearly invisible in aggregate metrics.
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            cache.put(TENANT, "what is the status of AWB 125", sample());
            assertTrue(cache.lookup(TENANT, "summarise the quarterly revenue forecast", ANY_TOKENS)
                    .isEmpty());
        }

        @Test
        @DisplayName("never serves one tenant's entry to another")
        void isolatesTenants() {
            // The prompts collide precisely because different customers ask the same
            // questions, so a shared cache leaks hardest on exactly the traffic it is
            // best at. An identical prompt from a different tenant must be a miss.
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            String prompt = "what is the outstanding balance on account 4471-8890";

            cache.put("acme", prompt, sample());

            assertTrue(cache.lookup("globex", prompt, ANY_TOKENS).isEmpty(),
                    "globex must not see acme's cached completion");
            assertTrue(cache.lookup("acme", prompt, ANY_TOKENS).isPresent(),
                    "acme must still hit its own entry");
            assertEquals(1, cache.sizeFor("acme"));
            assertEquals(0, cache.sizeFor("globex"));
        }

        @Test
        @DisplayName("keeps per-tenant entries separate under the same prompt")
        void storesPerTenantCopies() {
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            String prompt = "what is my current quota";

            cache.put("acme", prompt, new Completion("acme answer", 5, 5, 0.9, "stub"));
            cache.put("globex", prompt, new Completion("globex answer", 5, 5, 0.9, "stub"));

            assertEquals("acme answer",
                    cache.lookup("acme", prompt, ANY_TOKENS).orElseThrow().completion().text());
            assertEquals("globex answer",
                    cache.lookup("globex", prompt, ANY_TOKENS).orElseThrow().completion().text());
            assertEquals(2, cache.stats().size());
        }

        @Test
        @DisplayName("skips an entry longer than the caller's maxTokens")
        void respectsMaxTokens() {
            // Returning a 200-token cached answer to a caller who asked for 50 breaks a
            // limit they set deliberately, and truncating it would be a different answer.
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            cache.put(TENANT, "summarise the shipment", new Completion("long", 10, 200, 0.9, "stub"));

            assertTrue(cache.lookup(TENANT, "summarise the shipment", 50).isEmpty(),
                    "a 200-token entry must not serve a 50-token request");
            assertTrue(cache.lookup(TENANT, "summarise the shipment", 256).isPresent());
        }

        @Test
        @DisplayName("expires entries past the TTL")
        void expiresEntries() throws Exception {
            SemanticCache cache = new SemanticCache(
                    new HashingEmbedder(), 0.9, Duration.ofMillis(50), 100);
            cache.put(TENANT, "prompt one", sample());
            assertTrue(cache.lookup(TENANT, "prompt one", ANY_TOKENS).isPresent());

            Thread.sleep(80);
            assertTrue(cache.lookup(TENANT, "prompt one", ANY_TOKENS).isEmpty(),
                    "entry should have expired");
        }

        @Test
        @DisplayName("evicts least-recently-used entries at the cap")
        void evictsAtCapacity() {
            SemanticCache cache = new SemanticCache(
                    new HashingEmbedder(), 0.99, Duration.ofMinutes(10), 3);
            for (int i = 0; i < 6; i++) {
                cache.put(TENANT, "distinct prompt number " + i, sample());
            }
            assertEquals(3, cache.stats().size());
            assertTrue(cache.stats().evictions() >= 3);
        }

        @Test
        @DisplayName("keeps the tenant index in step with eviction")
        void evictionDoesNotStrandTheIndex() {
            // The scan index mirrors the LRU. If eviction forgets to unindex, the index
            // grows without bound and lookup starts scanning entries that no longer exist.
            SemanticCache cache = new SemanticCache(
                    new HashingEmbedder(), 0.99, Duration.ofMinutes(10), 3);
            for (int i = 0; i < 10; i++) {
                cache.put(TENANT, "distinct prompt number " + i, sample());
            }
            assertEquals(3, cache.stats().size());
            assertEquals(3, cache.sizeFor(TENANT), "index must not outlive the entries it mirrors");
        }

        @Test
        @DisplayName("tracks hit rate and mean similarity")
        void tracksStats() {
            SemanticCache cache = SemanticCache.withDefaults(new HashingEmbedder());
            cache.put(TENANT, "hello world", sample());
            cache.lookup(TENANT, "hello world", ANY_TOKENS);
            cache.lookup(TENANT, "totally different question about something else", ANY_TOKENS);

            SemanticCache.Stats stats = cache.stats();
            assertEquals(1, stats.hits());
            assertEquals(1, stats.misses());
            assertEquals(0.5, stats.hitRate(), 1e-6);
            assertTrue(stats.meanHitSimilarity() > 0.9);
        }

        @Test
        @DisplayName("rejects a nonsensical threshold")
        void validatesThreshold() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SemanticCache(new HashingEmbedder(), 0.0, Duration.ofMinutes(1), 10));
        }
    }

    @Nested
    @DisplayName("HashingEmbedder")
    class Embedding {

        @Test
        @DisplayName("is deterministic")
        void deterministic() {
            HashingEmbedder e = new HashingEmbedder();
            assertArrayEquals(e.embed("the same text"), e.embed("the same text"));
        }

        @Test
        @DisplayName("produces unit vectors")
        void producesUnitVectors() {
            float[] v = new HashingEmbedder().embed("some representative text here");
            double norm = 0;
            for (float f : v) norm += f * f;
            assertEquals(1.0, Math.sqrt(norm), 1e-5);
        }

        @Test
        @DisplayName("distinguishes word order via bigrams")
        void respectsWordOrder() {
            // A unigram bag would score these identical, so "is the flight held" would
            // collide with "held is the flight".
            HashingEmbedder e = new HashingEmbedder();
            double sim = Embedder.cosine(e.embed("is the flight held"), e.embed("held the flight is"));
            assertTrue(sim < 0.999, "bigrams should separate reordered text, got " + sim);
        }

        @Test
        @DisplayName("handles empty and punctuation-only input without blowing up")
        void handlesDegenerateInput() {
            HashingEmbedder e = new HashingEmbedder();
            assertDoesNotThrow(() -> e.embed(""));
            assertDoesNotThrow(() -> e.embed("!!! ??? ..."));
        }

        @Test
        @DisplayName("cosine of a vector with itself is 1")
        void selfSimilarityIsOne() {
            float[] v = new HashingEmbedder().embed("reference text");
            assertEquals(1.0, Embedder.cosine(v, v), 1e-6);
        }
    }

    @Nested
    @DisplayName("ModelRouter")
    class Routing {

        private final RetryExecutor fastRetry =
                new RetryExecutor(2, Duration.ofMillis(1), Duration.ofMillis(2));

        @Test
        @DisplayName("keeps a confident cheap answer without escalating")
        void staysOnCheapTierWhenConfident() throws Exception {
            StubProvider cheap = StubProvider.ok("cheap", 1, 0.25, 0.95);
            StubProvider pricey = StubProvider.ok("pricey", 2, 3.00, 0.99);
            ModelRouter router = new ModelRouter(List.of(pricey, cheap), fastRetry, 0.75);

            ModelRouter.Decision d = router.route("easy question", 100);

            assertEquals("cheap", d.completion().providerName());
            assertFalse(d.escalated());
            assertEquals(0, pricey.calls.get(), "the expensive tier must not be touched");
        }

        @Test
        @DisplayName("escalates when the cheap tier is not confident")
        void escalatesOnLowConfidence() throws Exception {
            StubProvider cheap = StubProvider.ok("cheap", 1, 0.25, 0.40);
            StubProvider pricey = StubProvider.ok("pricey", 2, 3.00, 0.96);
            ModelRouter router = new ModelRouter(List.of(cheap, pricey), fastRetry, 0.75);

            ModelRouter.Decision d = router.route("hard question", 100);

            assertEquals("pricey", d.completion().providerName());
            assertTrue(d.escalated());
            assertEquals(1, cheap.calls.get());
        }

        @Test
        @DisplayName("falls back to the best low-confidence answer when no tier clears the bar")
        void returnsBestAvailableWhenNoneConfident() throws Exception {
            StubProvider cheap = StubProvider.ok("cheap", 1, 0.25, 0.30);
            StubProvider pricey = StubProvider.ok("pricey", 2, 3.00, 0.50);
            ModelRouter router = new ModelRouter(List.of(cheap, pricey), fastRetry, 0.95);

            ModelRouter.Decision d = router.route("impossible question", 100);

            assertNotNull(d.completion(), "a weak answer beats no answer");
            assertEquals("pricey", d.completion().providerName());
            assertTrue(d.escalated());
        }

        @Test
        @DisplayName("the fallback is the highest-confidence answer, not the last tier tried")
        void fallbackPicksBestConfidenceNotLastTried() throws Exception {
            // Tiers are tried cheapest-first, not best-first. When the cheap tier scores
            // higher than the expensive one and neither clears the bar, returning "the
            // last one" means paying for two calls to hand back the worse answer.
            StubProvider cheap = StubProvider.ok("cheap", 1, 0.25, 0.70);
            StubProvider pricey = StubProvider.ok("pricey", 2, 3.00, 0.50);
            ModelRouter router = new ModelRouter(List.of(cheap, pricey), fastRetry, 0.95);

            ModelRouter.Decision d = router.route("hard question", 100);

            assertEquals("cheap", d.completion().providerName(),
                    "the 0.70 answer beats the 0.50 answer regardless of tier order");
            assertEquals(0.70, d.completion().confidence(), 1e-9);
            assertTrue(d.reason().contains("highest-confidence"));
        }

        @Test
        @DisplayName("the breaker counts attempts the retry loop absorbs")
        void breakerSeesFailuresHiddenByRetries() throws Exception {
            // A provider that fails twice and succeeds on the third attempt is degraded,
            // but every logical call still ends in success. Recording one outcome per
            // call would report a 100% success rate, so the breaker could never open
            // while every request silently paid the full backoff.
            FlakyProvider flaky = new FlakyProvider("flaky", 1, 2);
            StubProvider healthy = StubProvider.ok("healthy", 2, 3.00, 0.99);
            RetryExecutor retry = new RetryExecutor(3, Duration.ofMillis(1), Duration.ofMillis(2));
            ModelRouter router = new ModelRouter(List.of(flaky, healthy), retry, 0.75);

            for (int i = 0; i < 4; i++) {
                router.route("question " + i, 100);
            }

            assertTrue(flaky.successes.get() > 0, "every call should still have succeeded");
            assertEquals(CircuitBreaker.State.OPEN, router.breakerFor("flaky").state(),
                    "a provider failing 2 attempts in 3 must eventually trip the breaker");
        }

        @Test
        @DisplayName("a permanent failure inside the retry loop still does not trip the breaker")
        void permanentFailurePerAttemptDoesNotTripBreaker() throws Exception {
            StubProvider badRequest = StubProvider.failing("strict", 1, false);
            StubProvider healthy = StubProvider.ok("healthy", 2, 3.00, 0.99);
            ModelRouter router = new ModelRouter(List.of(badRequest, healthy), fastRetry, 0.75);

            for (int i = 0; i < 20; i++) {
                router.route("malformed " + i, 100);
            }
            assertEquals(CircuitBreaker.State.CLOSED, router.breakerFor("strict").state());
        }

        @Test
        @DisplayName("fails over to the next tier when one provider is down")
        void failsOverOnProviderFailure() throws Exception {
            StubProvider broken = StubProvider.failing("broken", 1, true);
            StubProvider healthy = StubProvider.ok("healthy", 2, 3.00, 0.99);
            ModelRouter router = new ModelRouter(List.of(broken, healthy), fastRetry, 0.75);

            ModelRouter.Decision d = router.route("question", 100);

            assertEquals("healthy", d.completion().providerName());
            assertTrue(d.attempted().contains("broken"));
        }

        @Test
        @DisplayName("throws when every provider is unavailable")
        void throwsWhenAllProvidersFail() {
            ModelRouter router = new ModelRouter(
                    List.of(StubProvider.failing("a", 1, true), StubProvider.failing("b", 2, true)),
                    fastRetry, 0.75);
            assertThrows(ProviderException.class, () -> router.route("question", 100));
        }

        @Test
        @DisplayName("skips a provider whose breaker is open instead of paying for a timeout")
        void skipsOpenBreaker() throws Exception {
            StubProvider broken = StubProvider.failing("broken", 1, true);
            StubProvider healthy = StubProvider.ok("healthy", 2, 3.00, 0.99);
            ModelRouter router = new ModelRouter(List.of(broken, healthy), fastRetry, 0.75);

            // Drive the breaker open.
            for (int i = 0; i < 12; i++) {
                router.route("question " + i, 100);
            }
            assertEquals(CircuitBreaker.State.OPEN, router.breakerFor("broken").state());

            int callsBefore = broken.calls.get();
            ModelRouter.Decision d = router.route("another question", 100);

            assertEquals(callsBefore, broken.calls.get(), "an open breaker must not call the provider");
            assertEquals("healthy", d.completion().providerName());
        }

        @Test
        @DisplayName("a permanent failure does not trip the breaker")
        void permanentFailureDoesNotTripBreaker() throws Exception {
            // A malformed prompt is the caller's fault; it must not take a healthy
            // provider out of rotation for everyone else.
            StubProvider badRequest = StubProvider.failing("strict", 1, false);
            StubProvider healthy = StubProvider.ok("healthy", 2, 3.00, 0.99);
            ModelRouter router = new ModelRouter(List.of(badRequest, healthy), fastRetry, 0.75);

            for (int i = 0; i < 15; i++) {
                router.route("malformed " + i, 100);
            }
            assertEquals(CircuitBreaker.State.CLOSED, router.breakerFor("strict").state());
        }

        @Test
        @DisplayName("orders providers by tier regardless of input order")
        void sortsProvidersByTier() {
            ModelRouter router = new ModelRouter(
                    List.of(StubProvider.ok("t3", 3, 5.0, 0.9),
                            StubProvider.ok("t1", 1, 0.2, 0.9),
                            StubProvider.ok("t2", 2, 1.0, 0.9)),
                    fastRetry, 0.75);
            assertEquals(List.of("t1", "t2", "t3"),
                    router.providers().stream().map(LlmProvider::name).toList());
        }

        @Test
        @DisplayName("rejects construction with no providers")
        void requiresAtLeastOneProvider() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ModelRouter(List.of(), fastRetry, 0.75));
        }
    }
}
