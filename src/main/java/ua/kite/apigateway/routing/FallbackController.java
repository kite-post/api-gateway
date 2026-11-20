package ua.kite.apigateway.routing;

import java.util.concurrent.TimeoutException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import ua.kite.apigateway.web.Problems;

/**
 * Відповідь, коли сервіс не відповів: тайм-аут — 504, відкритий breaker або недоступний сервіс —
 * 503. Сюди потрапляють лише внутрішні forward-и з circuit breaker; прямий запит отримує 404.
 */
@RestController
class FallbackController {

    static final String PATH = "/__gateway/fallback";

    private final Problems problems;

    FallbackController(Problems problems) {
        this.problems = problems;
    }

    @RequestMapping(PATH)
    Mono<Void> fallback(ServerWebExchange exchange) {
        Throwable error = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (error == null) {
            return problems.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "Не знайдено", null);
        }
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String service = route != null ? route.getId() : "сервіс";

        if (hasCause(error, CallNotPermittedException.class)) {
            return problems.write(
                    exchange,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UPSTREAM_CIRCUIT_OPEN",
                    "Сервіс тимчасово недоступний",
                    "Маршрут " + service + " тимчасово вимкнено після серії збоїв. Спробуйте за кілька секунд.");
        }
        if (hasCause(error, TimeoutException.class)) {
            return problems.write(
                    exchange,
                    HttpStatus.GATEWAY_TIMEOUT,
                    "UPSTREAM_TIMEOUT",
                    "Сервіс не відповів вчасно",
                    "Маршрут " + service + " не відповів за відведений час.");
        }
        return problems.write(
                exchange,
                HttpStatus.SERVICE_UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
                "Сервіс недоступний",
                "Маршрут " + service + " зараз недоступний.");
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
