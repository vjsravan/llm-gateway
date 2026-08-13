package dev.jsv.gateway.routing;

import dev.jsv.gateway.provider.Completion;
import dev.jsv.gateway.provider.LlmProvider;
import dev.jsv.gateway.provider.ProviderException;
import dev.jsv.gateway.resilience.CircuitBreaker;
import dev.jsv.gateway.resilience.RetryExecutor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes a request to the cheapest provider likely to answer it well, escalating to a
 * more capable tier only when the cheap one returns low confidence.
 *
 * <p>The economics: most production traffic is easy, and paying a frontier-model price
 * for "what is the status of this shipment" is waste. Escalating on confidence keeps
 * the hard questions on the strong model while the routine bulk goes to the cheap one.
 *
 * <p>The failure mode this design has to avoid is escalating on *everything*, which
 * costs strictly more than never routing at all — you pay the cheap call and then the
 * expensive one. The confidence threshold is therefore reported in metrics and should
 * be tuned against a real eval suite, not guessed. (This is precisely what the
 * companion llmeval project exists to measure: whether cheap-tier answers are actually
 * good enough to keep.)
 *
 * <p>Providers whose breaker is open are skipped rather than tried and failed, so an
 * unhealthy provider costs nothing instead of costing a timeout per request.
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final List<LlmProvider> providers;
    private final Map<String, CircuitBreaker> breakers = new HashMap<>();
    private final RetryExecutor retry;
    private final double escalationThreshold;

    public ModelRouter(List<LlmProvider> providers, RetryExecutor retry, double escalationThreshold) {
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("router needs at least one provider");
        }
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(LlmProvider::tier))
                .toList();
        this.retry = retry;
        this.escalationThreshold = escalationThreshold;
        this.providers.forEach(p -> breakers.put(p.name(), CircuitBreaker.withDefaults(p.name())));
    }

    /** Outcome of routing, including the path taken — needed for cost attribution. */
    public record Decision(
            Completion completion,
            List<String> attempted,
            boolean escalated,
            String reason
    ) {}

    /**
     * @throws ProviderException when every eligible provider fails or is broken open
     */
    public Decision route(String prompt, int maxTokens) throws ProviderException {
        List<String> attempted = new ArrayList<>();
        Completion bestBelowThreshold = null;
        String firstLowConfidenceProvider = null;
        ProviderException lastFailure = null;

        for (LlmProvider provider : providers) {
            CircuitBreaker breaker = breakers.get(provider.name());

            if (!breaker.allowRequest()) {
                log.debug("skipping {} — breaker {}", provider.name(), breaker.state());
                attempted.add(provider.name() + ":skipped-breaker-open");
                continue;
            }

            attempted.add(provider.name());
            try {
                // The breaker is fed from *inside* the retry loop, so it sees every
                // attempt rather than one verdict per logical call. Recording only the
                // final outcome hides the most common degradation there is: a provider
                // failing two attempts in three still succeeds, so it would report a
                // 100% success rate and the breaker could never open — while every
                // request quietly pays the full backoff.
                Completion completion = retry.execute(
                        () -> {
                            try {
                                Completion c = provider.complete(prompt, maxTokens);
                                breaker.recordSuccess();
                                return c;
                            } catch (ProviderException attemptFailure) {
                                // Permanent failures stay out of the window: a malformed
                                // prompt is the caller's fault, and letting it open the
                                // circuit lets one bad client deny service to everyone.
                                if (attemptFailure.isRetryable()) {
                                    breaker.recordFailure();
                                }
                                throw attemptFailure;
                            }
                        },
                        (attempt, sleepMs, cause) -> log.debug(
                                "retry {} of {} for {} in {}ms: {}",
                                attempt, retry.maxAttempts(), provider.name(), sleepMs, cause.getMessage())
                );

                if (completion.confidence() >= escalationThreshold) {
                    return new Decision(
                            completion,
                            attempted,
                            firstLowConfidenceProvider != null,
                            firstLowConfidenceProvider == null
                                    ? "confidence %.2f met threshold on first tier".formatted(completion.confidence())
                                    : "escalated after low-confidence answer from " + firstLowConfidenceProvider
                    );
                }

                // Good enough to keep as a fallback, not good enough to return yet. Keep
                // the highest-confidence one rather than the most recent: tiers are tried
                // cheapest-first, not best-first, so the last tier is not necessarily the
                // strongest answer and returning it would mean paying for two calls to
                // hand back the worse of the two.
                if (bestBelowThreshold == null
                        || completion.confidence() > bestBelowThreshold.confidence()) {
                    bestBelowThreshold = completion;
                }
                if (firstLowConfidenceProvider == null) {
                    firstLowConfidenceProvider = provider.name();
                }
                log.debug("confidence {} below threshold {} from {} — escalating",
                        completion.confidence(), escalationThreshold, provider.name());

            } catch (ProviderException e) {
                lastFailure = e;
                log.warn("provider {} failed ({}): {}", provider.name(),
                        e.isRetryable() ? "retryable" : "permanent", e.getMessage());
            }
        }

        // Every tier was tried. A low-confidence answer beats no answer.
        if (bestBelowThreshold != null) {
            return new Decision(bestBelowThreshold, attempted, true,
                    "all tiers below confidence threshold, returning highest-confidence answer (%.2f from %s)"
                            .formatted(bestBelowThreshold.confidence(), bestBelowThreshold.providerName()));
        }

        throw new ProviderException("router",
                "all providers failed or are unavailable, attempted=" + attempted,
                lastFailure != null && lastFailure.isRetryable(), lastFailure);
    }

    public Map<String, CircuitBreaker.State> breakerStates() {
        Map<String, CircuitBreaker.State> out = new HashMap<>();
        breakers.forEach((name, cb) -> out.put(name, cb.state()));
        return out;
    }

    public CircuitBreaker breakerFor(String providerName) {
        return breakers.get(providerName);
    }

    public List<LlmProvider> providers() {
        return providers;
    }

    public double escalationThreshold() {
        return escalationThreshold;
    }
}
