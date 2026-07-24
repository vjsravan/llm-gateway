package dev.jsv.gateway.provider;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deterministic-ish provider used for local runs, demos and load tests.
 *
 * <p>It exists so the gateway is runnable and benchmarkable with no API keys and no
 * network, and — more usefully — so failure behaviour can be exercised on demand.
 * Injecting a 40% error rate against a real provider is impractical; here it is a
 * constructor argument, which is what makes the circuit breaker and retry paths
 * genuinely testable rather than merely written.
 */
public class SimulatedProvider implements LlmProvider {

    private final String name;
    private final int tier;
    private final double costPer1k;
    private final long latencyMs;
    private final double failureRate;
    private final double baseConfidence;

    private final AtomicLong calls = new AtomicLong();

    public SimulatedProvider(String name, int tier, double costPer1k, long latencyMs,
                             double failureRate, double baseConfidence) {
        this.name = name;
        this.tier = tier;
        this.costPer1k = costPer1k;
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
        this.baseConfidence = baseConfidence;
    }

    /**
     * A fast, cheap, slightly unreliable small model.
     *
     * <p>Base confidence sits above the default 0.75 escalation threshold so short,
     * routine prompts are served here, while the length penalty pushes longer
     * open-ended prompts below the bar and on to the larger model. Tuned deliberately:
     * an earlier value of 0.62 sat under the threshold for every prompt, so the router
     * paid for a cheap call *and* an expensive one on every request — strictly worse
     * than not routing at all, and invisible unless you look at per-provider counts.
     */
    public static SimulatedProvider smallModel() {
        return new SimulatedProvider("sim-small", 1, 0.25, 40, 0.02, 0.86);
    }

    /** A slower, pricier, more capable model. */
    public static SimulatedProvider largeModel() {
        return new SimulatedProvider("sim-large", 2, 3.00, 180, 0.01, 0.94);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public double costPer1kTokens() {
        return costPer1k;
    }

    @Override
    public int tier() {
        return tier;
    }

    @Override
    public Completion complete(String prompt, int maxTokens) throws ProviderException {
        calls.incrementAndGet();

        try {
            // Jitter the latency so percentile metrics are not a single flat value.
            long jitter = ThreadLocalRandom.current().nextLong(-latencyMs / 4, latencyMs / 2 + 1);
            Thread.sleep(Math.max(1, latencyMs + jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(name, "interrupted", false, e);
        }

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new ProviderException(name, "simulated upstream 503", true);
        }

        // Confidence drifts with prompt length: longer, more open-ended prompts get a
        // lower score, which is what makes tier escalation observable in the demo.
        double lengthPenalty = Math.min(0.25, prompt.length() / 4000.0);
        double confidence = Math.max(0.0, Math.min(1.0, baseConfidence - lengthPenalty));

        String text = "[" + name + "] " + summarise(prompt);
        int promptTokens = Math.max(1, prompt.length() / 4);
        int outputTokens = Math.min(maxTokens, Math.max(1, text.length() / 4));

        return new Completion(text, promptTokens, outputTokens, confidence, name);
    }

    private static String summarise(String prompt) {
        String trimmed = prompt.strip().replaceAll("\\s+", " ");
        return trimmed.length() <= 120 ? "response to: " + trimmed
                                       : "response to: " + trimmed.substring(0, 120) + "…";
    }

    public long callCount() {
        return calls.get();
    }
}
