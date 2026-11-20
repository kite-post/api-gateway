package ua.kite.apigateway.routing;

import java.util.List;
import java.util.Optional;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Карта {@code /api/v1} → сервіс за таблицями 9.2–9.5 і відповідальністю сервісів із 5.1.
 *
 * <p>Порядок важливий: перший збіг виграє, тож вужчі шляхи стоять перед ширшими ({@code
 * /me/bonuses} до {@code /me/**}, {@code /shipments/{id}/timeline} до {@code /shipments/**}).
 * Шлях іде в сервіс без змін — сервіси самі слухають під {@code /api/v1}.
 *
 * <p>Нового ендпоїнта, якого тут немає, шлюз не пропустить: він відповість 404. Це свідомо —
 * назовні виходить лише те, що описано в контракті.
 */
public final class RouteTable {

    /** Бакет rate limit для всього, крім входу. */
    public static final String DEFAULT_BUCKET = "default";

    /** Бакет для входу: OTP і перевірка коду — найпростіше місце для перебору (9.2). */
    public static final String AUTH_BUCKET = "auth";

    /**
     * Шляхи без токена — ті самі, що мають {@code security: []} в {@code openapi.yaml} (9.2).
     * Решта {@code /api/v1} вимагає валідного JWT.
     */
    public static final List<String> PUBLIC_PATHS =
            List.of("/api/v1/auth/otp", "/api/v1/auth/otp/verify", "/api/v1/auth/refresh", "/api/v1/public/**");

    public record Route(String id, String service, String bucket, List<String> paths) {}

    public static final List<Route> ROUTES = List.of(
            // 9.2 Публічні та автентифікація
            authRoute("auth", "customer", "/api/v1/auth/**"),
            route("public-track", "tracking", "/api/v1/public/track/**"),
            route("public-locations", "network", "/api/v1/public/locations/**"),
            route("public-quotes", "pricing", "/api/v1/public/quotes/**"),

            // 9.3 Клієнт
            route("me-bonuses", "payment", "/api/v1/me/bonuses/**"),
            route("me-external-tracking", "tracking", "/api/v1/me/external-tracking/**"),
            route("me", "customer", "/api/v1/me/**"),
            route("shipment-timeline", "tracking", "/api/v1/shipments/*/timeline"),
            route("shipments", "shipment", "/api/v1/shipments/**"),
            route("payments", "payment", "/api/v1/payments/**"),
            route("promo-codes", "pricing", "/api/v1/promo-codes/**"),
            route("lockers", "locker", "/api/v1/lockers/**", "/api/v1/storage-bookings/**"),
            route("care", "care", "/api/v1/claims/**", "/api/v1/tickets/**"),

            // 9.4 Kite Business
            route("business", "business", "/api/v1/business/**"),

            // 9.5 Kite Ops
            route("scans", "scan", "/api/v1/scans/**", "/api/v1/ops/hubs/*/sort-hint/**"),
            route("scanner-pairing", "realtime", "/api/v1/scanner/**", "/api/v1/ops/scanner-sessions/**"),
            route(
                    "ops-shipments",
                    "shipment",
                    "/api/v1/ops/shipments/**",
                    "/api/v1/ops/acceptances/**",
                    "/api/v1/ops/handovers/**"),
            route(
                    "ops-logistics",
                    "logistics",
                    "/api/v1/ops/units/**",
                    "/api/v1/ops/trips/**",
                    "/api/v1/ops/discrepancies/**",
                    // Диспетчерська: інциденти й навантаження мережі будуються з рейсів і
                    // розбіжностей. Якщо їх забере інший сервіс — переставити тут.
                    "/api/v1/ops/incidents/**",
                    "/api/v1/ops/network/**"),
            route(
                    "ops-network",
                    "network",
                    "/api/v1/ops/locations/**",
                    "/api/v1/ops/routing-rules/**",
                    "/api/v1/ops/sort-lines/**",
                    "/api/v1/ops/schedules/**"),
            route("ops-pricing", "pricing", "/api/v1/ops/tariffs/**", "/api/v1/ops/promo-codes/**"),
            // Фасад над Keycloak і network (9.5); персонал — теж профіль, тож customer-service.
            route("ops-users", "customer", "/api/v1/ops/users/**"),
            route("ops-care", "care", "/api/v1/ops/claims/**", "/api/v1/ops/tickets/**"),
            route("ops-audit", "audit", "/api/v1/ops/audit/**"));

    private RouteTable() {}

    private static Route route(String id, String service, String... paths) {
        return new Route(id, service, DEFAULT_BUCKET, List.of(paths));
    }

    private static Route authRoute(String id, String service, String... paths) {
        return new Route(id, service, AUTH_BUCKET, List.of(paths));
    }

    /** Який маршрут забере шлях — тим самим парсером, що й шлюз. Для тестів і діагностики. */
    public static Optional<Route> match(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return ROUTES.stream()
                .filter(r -> r.paths().stream()
                        .anyMatch(
                                p -> PathPatternParser.defaultInstance.parse(p).matches(container)))
                .findFirst();
    }
}
