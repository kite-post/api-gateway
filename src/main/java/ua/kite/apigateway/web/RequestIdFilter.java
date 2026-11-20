package ua.kite.apigateway.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@code X-Request-Id} приймається від клієнта або генерується шлюзом і йде далі в сервіс та у
 * відповідь (9.1). Фільтр стоїть першим — раніше за безпеку, — щоб і відмова 401 мала requestId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String ATTR = RequestIdFilter.class.getName() + ".requestId";
    private static final String PATH_ATTR = RequestIdFilter.class.getName() + ".path";

    /** Чуже значення пускаємо далі, лише якщо воно схоже на ідентифікатор, а не на ін'єкцію в логи. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(h -> h.set(HEADER, requestId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTR, requestId);
        // Шлях клієнта до внутрішніх forward-ів (fallback breaker-а) — для поля instance.
        mutated.getAttributes().put(PATH_ATTR, exchange.getRequest().getPath().value());
        mutated.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(mutated);
    }

    public static String requestId(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTR);
    }

    /** Шлях, з яким прийшов клієнт, навіть якщо запит уже переслано на fallback. */
    public static String originalPath(ServerWebExchange exchange) {
        String path = exchange.getAttribute(PATH_ATTR);
        return path != null ? path : exchange.getRequest().getPath().value();
    }
}
