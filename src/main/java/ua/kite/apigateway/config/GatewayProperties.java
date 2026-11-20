package ua.kite.apigateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Налаштування шлюзу з {@code application.yml} (префікс {@code kite.gateway}).
 *
 * @param services адреси сервісів за коротким ім'ям: {@code shipment → http://shipment-service:8080}
 * @param timeout тайм-аут виклику сервіса (5.4, п. 7) — і для HTTP-клієнта, і для time limiter
 * @param rateLimits бакети rate limit за іменем: {@code default} для всього, {@code auth} для входу
 * @param circuitBreaker параметри circuit breaker, однакові для всіх маршрутів
 */
@ConfigurationProperties("kite.gateway")
public record GatewayProperties(
        Map<String, URI> services, Duration timeout, Map<String, RateLimit> rateLimits, CircuitBreaker circuitBreaker) {

    /**
     * Token bucket у Redis: бакет поповнюється на {@code replenishRate} токенів щосекунди, вміщує
     * {@code burstCapacity}, запит коштує {@code requestedTokens}. Так виходять і ліміти «за
     * хвилину»: 1 токен/с, ціна 6 — це 10 запитів на хвилину.
     */
    public record RateLimit(int replenishRate, int burstCapacity, int requestedTokens) {}

    /**
     * @param slidingWindowSize скільки останніх викликів оцінюється
     * @param minimumNumberOfCalls менше викликів — breaker не відкривається
     * @param failureRateThreshold відсоток помилок, з якого breaker відкривається
     * @param waitDurationInOpenState скільки breaker відкритий, перш ніж пробувати знову
     */
    public record CircuitBreaker(
            int slidingWindowSize,
            int minimumNumberOfCalls,
            float failureRateThreshold,
            Duration waitDurationInOpenState) {}

    public URI service(String name) {
        URI uri = services.get(name);
        if (uri == null) {
            throw new IllegalStateException("Немає адреси сервіса kite.gateway.services." + name);
        }
        return uri;
    }

    public RateLimit rateLimit(String bucket) {
        RateLimit limit = rateLimits.get(bucket);
        if (limit == null) {
            throw new IllegalStateException("Немає ліміту kite.gateway.rate-limits." + bucket);
        }
        return limit;
    }
}
