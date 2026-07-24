package dev.jsv.gateway;

import dev.jsv.gateway.budget.TenantBudget;
import dev.jsv.gateway.cache.HashingEmbedder;
import dev.jsv.gateway.cache.SemanticCache;
import dev.jsv.gateway.observability.GatewayMetrics;
import dev.jsv.gateway.provider.LlmProvider;
import dev.jsv.gateway.provider.SimulatedProvider;
import dev.jsv.gateway.resilience.RetryExecutor;
import dev.jsv.gateway.routing.ModelRouter;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Boots the gateway with simulated providers so it runs with no API keys.
 *
 * <p>Swapping in real providers means implementing {@link LlmProvider} and replacing
 * the list in {@link #router}. Nothing else in the pipeline changes — that separation
 * is the reason the reliability logic is testable at all.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public SemanticCache semanticCache(GatewayProperties props) {
        return new SemanticCache(
                new HashingEmbedder(props.getEmbeddingDimensions()),
                props.getCacheSimilarityThreshold(),
                Duration.ofSeconds(props.getCacheTtlSeconds()),
                props.getCacheMaxEntries());
    }

    @Bean
    public ModelRouter router(GatewayProperties props) {
        List<LlmProvider> providers = List.of(
                SimulatedProvider.smallModel(),
                SimulatedProvider.largeModel());
        RetryExecutor retry = new RetryExecutor(
                props.getMaxRetryAttempts(),
                Duration.ofMillis(props.getRetryBaseDelayMs()),
                Duration.ofMillis(props.getRetryMaxDelayMs()));
        return new ModelRouter(providers, retry, props.getEscalationConfidenceThreshold());
    }

    @Bean
    public TenantBudget tenantBudget(GatewayProperties props) {
        return new TenantBudget(
                props.getRateLimitBurst(),
                props.getRateLimitPerSecond(),
                props.getTokenQuotaPerWindow(),
                Duration.ofSeconds(props.getQuotaWindowSeconds()));
    }

    @Bean
    public GatewayMetrics gatewayMetrics() {
        return new GatewayMetrics();
    }
}
