package dev.jsv.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalised knobs, bound from {@code gateway.*} in application.yml. */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /**
     * Cosine similarity a cached prompt must reach to be reused. Conservative on
     * purpose: a false hit serves a confidently wrong answer, which is worse than a
     * miss and much harder to notice.
     */
    private double cacheSimilarityThreshold = 0.95;

    private long cacheTtlSeconds = 900;
    private int cacheMaxEntries = 5_000;
    private int embeddingDimensions = 256;

    /** Below this confidence, the router escalates to a more capable tier. */
    private double escalationConfidenceThreshold = 0.75;

    private int maxRetryAttempts = 3;
    private long retryBaseDelayMs = 120;
    private long retryMaxDelayMs = 4_000;

    private int rateLimitBurst = 20;
    private double rateLimitPerSecond = 5.0;
    private long tokenQuotaPerWindow = 200_000;
    private long quotaWindowSeconds = 3_600;

    public double getCacheSimilarityThreshold() { return cacheSimilarityThreshold; }
    public void setCacheSimilarityThreshold(double v) { this.cacheSimilarityThreshold = v; }

    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long v) { this.cacheTtlSeconds = v; }

    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public void setCacheMaxEntries(int v) { this.cacheMaxEntries = v; }

    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int v) { this.embeddingDimensions = v; }

    public double getEscalationConfidenceThreshold() { return escalationConfidenceThreshold; }
    public void setEscalationConfidenceThreshold(double v) { this.escalationConfidenceThreshold = v; }

    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int v) { this.maxRetryAttempts = v; }

    public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public void setRetryBaseDelayMs(long v) { this.retryBaseDelayMs = v; }

    public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
    public void setRetryMaxDelayMs(long v) { this.retryMaxDelayMs = v; }

    public int getRateLimitBurst() { return rateLimitBurst; }
    public void setRateLimitBurst(int v) { this.rateLimitBurst = v; }

    public double getRateLimitPerSecond() { return rateLimitPerSecond; }
    public void setRateLimitPerSecond(double v) { this.rateLimitPerSecond = v; }

    public long getTokenQuotaPerWindow() { return tokenQuotaPerWindow; }
    public void setTokenQuotaPerWindow(long v) { this.tokenQuotaPerWindow = v; }

    public long getQuotaWindowSeconds() { return quotaWindowSeconds; }
    public void setQuotaWindowSeconds(long v) { this.quotaWindowSeconds = v; }
}
