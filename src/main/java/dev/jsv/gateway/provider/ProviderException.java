package dev.jsv.gateway.provider;

/**
 * A provider call failed.
 *
 * <p>{@code retryable} is the important field: a 429 or a socket timeout should be
 * retried and should trip the breaker, while a 400 for a malformed prompt should do
 * neither. Treating every failure identically is how a single bad request takes a
 * healthy provider out of rotation.
 */
public class ProviderException extends Exception {

    private final boolean retryable;
    private final String provider;

    public ProviderException(String provider, String message, boolean retryable) {
        super(message);
        this.provider = provider;
        this.retryable = retryable;
    }

    public ProviderException(String provider, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getProvider() {
        return provider;
    }
}
