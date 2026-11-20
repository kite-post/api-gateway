package ua.kite.apigateway.ratelimit;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Map;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import ua.kite.apigateway.web.Problems;

/**
 * Rate limit на Redis: token bucket {@link RedisRateLimiter} на кожного користувача (за {@code
 * sub} з JWT) або, без токена, на IP. Свій фільтр замість штатного {@code RequestRateLimiter}
 * лише заради тіла відповіді: штатний віддає порожню 429, а контракт вимагає problem+json (9.1).
 *
 * <p>Якщо Redis недоступний, {@link RedisRateLimiter} пропускає запит — краще без ліміту, ніж без
 * API.
 */
public class RateLimitFilter implements GatewayFilter {

    private final RedisRateLimiter limiter;
    private final String bucket;
    private final Problems problems;

    public RateLimitFilter(RedisRateLimiter limiter, String bucket, Problems problems) {
        this.limiter = limiter;
        this.bucket = bucket;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return key(exchange)
                // Ключ включає бакет: ліміт входу й загальний ліміт рахуються окремо.
                .flatMap(key -> limiter.isAllowed(bucket, bucket + ":" + key))
                .flatMap(result -> {
                    for (Map.Entry<String, String> header : result.getHeaders().entrySet()) {
                        exchange.getResponse().getHeaders().add(header.getKey(), header.getValue());
                    }
                    if (result.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    return problems.write(
                            exchange,
                            HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMITED",
                            "Забагато запитів",
                            "Перевищено ліміт частоти запитів. Спробуйте трохи пізніше.");
                });
    }

    private static Mono<String> key(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(p -> !(p instanceof AnonymousAuthenticationToken))
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        // На стенді перед шлюзом проксі немає, тож X-Forwarded-For не довіряємо:
        // інакше обійти ліміт можна одним заголовком.
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }
}
