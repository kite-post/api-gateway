package ua.kite.apigateway.routing;

import java.util.List;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import ua.kite.apigateway.config.GatewayProperties;
import ua.kite.apigateway.ratelimit.RateLimitFilter;
import ua.kite.apigateway.web.Problems;

/**
 * Маршрути з {@link RouteTable}: кожен — rate limit, потім circuit breaker із тайм-аутом, потім
 * сервіс. Тайм-аут HTTP-клієнта (2 с) задано в {@code application.yml}; той самий час стоїть і в
 * time limiter breaker-а, тож повільний сервіс і рахується як збій, і не тримає клієнта довше.
 */
@Configuration(proxyBeanMethods = false)
public class RoutesConfig {

    /** Куди breaker передає запит, коли сервіс не відповів: там збирається problem+json. */
    static final String FALLBACK = "forward:" + FallbackController.PATH;

    @Bean
    RouteLocator kiteRoutes(
            RouteLocatorBuilder builder, GatewayProperties props, RedisRateLimiter limiter, Problems problems) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (RouteTable.Route route : RouteTable.ROUTES) {
            RateLimitFilter rateLimit = new RateLimitFilter(limiter, route.bucket(), problems);
            routes.route(
                    route.id(),
                    r -> r.path(route.paths().toArray(String[]::new))
                            .filters(f -> f.filter(rateLimit)
                                    .circuitBreaker(cb -> cb.setName(route.id()).setFallbackUri(FALLBACK)))
                            .uri(props.service(route.service())));
        }
        return routes.build();
    }

    /**
     * Свій {@link RedisRateLimiter} замість штатного: бакети ({@code default}, {@code auth})
     * беруться з {@code kite.gateway.rate-limits}, а не з налаштувань маршрутів.
     */
    @Bean
    RedisRateLimiter redisRateLimiter(
            ReactiveStringRedisTemplate redis,
            @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
            ConfigurationService configurationService,
            GatewayProperties props) {
        RedisRateLimiter limiter = new RedisRateLimiter(redis, script, configurationService);
        props.rateLimits()
                .forEach((bucket, limit) -> limiter.getConfig()
                        .put(
                                bucket,
                                new RedisRateLimiter.Config()
                                        .setReplenishRate(limit.replenishRate())
                                        .setBurstCapacity(limit.burstCapacity())
                                        .setRequestedTokens(limit.requestedTokens())));
        return limiter;
    }

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerDefaults(GatewayProperties props) {
        GatewayProperties.CircuitBreaker cb = props.circuitBreaker();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(cb.slidingWindowSize())
                        .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                        .failureRateThreshold(cb.failureRateThreshold())
                        .waitDurationInOpenState(cb.waitDurationInOpenState())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(props.timeout())
                        .build())
                .build());
    }
}
