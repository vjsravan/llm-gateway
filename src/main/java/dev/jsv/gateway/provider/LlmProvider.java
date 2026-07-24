package dev.jsv.gateway.provider;

/**
 * A backend that can answer a completion request.
 *
 * <p>Providers are deliberately dumb: they do one call and either return or throw. All
 * retrying, breaking, caching and routing lives above them in the gateway, so adding a
 * new provider never means reimplementing reliability logic.
 */
public interface LlmProvider {

    /** Stable identifier used in metrics, logs and routing rules. */
    String name();

    /** Relative cost per 1K tokens. Used by the router to prefer cheaper tiers. */
    double costPer1kTokens();

    /**
     * Capability tier. The router escalates upward when a cheaper tier returns a
     * low-confidence answer.
     */
    int tier();

    /**
     * @throws ProviderException when the call fails in a way the caller may retry
     */
    Completion complete(String prompt, int maxTokens) throws ProviderException;
}
