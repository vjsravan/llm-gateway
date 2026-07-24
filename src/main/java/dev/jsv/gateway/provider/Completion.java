package dev.jsv.gateway.provider;

/**
 * One provider response.
 *
 * @param text          the completion
 * @param promptTokens  tokens consumed by the prompt
 * @param outputTokens  tokens produced
 * @param confidence    provider-reported or gateway-estimated confidence in [0,1];
 *                      drives tier escalation in {@link dev.jsv.gateway.routing.ModelRouter}
 * @param providerName  which backend actually served this
 */
public record Completion(
        String text,
        int promptTokens,
        int outputTokens,
        double confidence,
        String providerName
) {
    public Completion {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }

    public int totalTokens() {
        return promptTokens + outputTokens;
    }
}
