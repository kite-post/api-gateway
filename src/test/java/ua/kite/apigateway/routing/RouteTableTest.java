package ua.kite.apigateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

/** Таблиця маршрутів проти ендпоїнтів із 9.2–9.5: кожен шлях іде в сервіс, що ним володіє (5.1). */
class RouteTableTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "POST, /api/v1/auth/otp, customer",
        "POST, /api/v1/auth/otp/verify, customer",
        "POST, /api/v1/auth/refresh, customer",
        "GET, /api/v1/public/track/KT123456785UA, tracking",
        "GET, /api/v1/public/locations, network",
        "GET, /api/v1/public/locations/42, network",
        "GET, /api/v1/public/quotes, pricing",
        "GET, /api/v1/me, customer",
        "GET, /api/v1/me/phones/1/verify, customer",
        "GET, /api/v1/me/bonuses, payment",
        "GET, /api/v1/me/external-tracking, tracking",
        "GET, /api/v1/me/external-tracking/7, tracking",
        "POST, /api/v1/shipments, shipment",
        "GET, /api/v1/shipments, tracking",
        "PATCH, /api/v1/shipments/abc, shipment",
        "GET, /api/v1/shipments/abc, shipment",
        "POST, /api/v1/shipments/abc/confirm, shipment",
        "POST, /api/v1/shipments/abc/actions/redirect, shipment",
        "GET, /api/v1/shipments/abc/timeline, tracking",
        "GET, /api/v1/payments, payment",
        "GET, /api/v1/payments/p1, payment",
        "GET, /api/v1/promo-codes/validate, pricing",
        "POST, /api/v1/lockers/loc-1/open, locker",
        "GET, /api/v1/storage-bookings, locker",
        "GET, /api/v1/claims, care",
        "POST, /api/v1/claims/c1/attachments, care",
        "POST, /api/v1/tickets/t1/messages, care",
        "GET, /api/v1/business/org, business",
        "POST, /api/v1/business/webhooks/w1/test, business",
        "POST, /api/v1/scans, scan",
        "GET, /api/v1/ops/hubs/h1/sort-hint/KT123456785UA, scan",
        "POST, /api/v1/scanner/pair, realtime",
        "GET, /api/v1/ops/scanner-sessions, realtime",
        "GET, /api/v1/ops/shipments/search, shipment",
        "GET, /api/v1/ops/acceptances, shipment",
        "GET, /api/v1/ops/handovers, shipment",
        "POST, /api/v1/ops/units/u1/close, logistics",
        "POST, /api/v1/ops/trips/t1/depart, logistics",
        "GET, /api/v1/ops/discrepancies, logistics",
        "GET, /api/v1/ops/locations, network",
        "GET, /api/v1/ops/routing-rules, network",
        "GET, /api/v1/ops/tariffs, pricing",
        "GET, /api/v1/ops/promo-codes, pricing",
        "GET, /api/v1/ops/users, customer",
        "GET, /api/v1/ops/claims, care",
        "GET, /api/v1/ops/audit, audit",
    })
    void shliakhIdeVSvoiServis(HttpMethod method, String path, String service) {
        assertThat(RouteTable.match(method, path))
                .as(method + " " + path)
                .hasValueSatisfying(route -> assertThat(route.service()).isEqualTo(service));
    }

    @Test
    void lyshVkhidMaieSuvorishyiLimit() {
        assertThat(RouteTable.match(HttpMethod.GET, "/api/v1/auth/otp")
                        .orElseThrow()
                        .bucket())
                .isEqualTo(RouteTable.AUTH_BUCKET);
        assertThat(RouteTable.match(HttpMethod.GET, "/api/v1/public/quotes")
                        .orElseThrow()
                        .bucket())
                .isEqualTo(RouteTable.DEFAULT_BUCKET);
    }

    @Test
    void nevidomyiShliakhNikudyNeIde() {
        assertThat(RouteTable.match(HttpMethod.GET, "/api/v1/unknown")).isEmpty();
        assertThat(RouteTable.match(HttpMethod.GET, "/actuator/health")).isEmpty();
        assertThat(RouteTable.match(HttpMethod.GET, "/api/v1/shipmentsX")).isEmpty();
    }

    @Test
    void dashbordLyshePoKolektsii_aNePoVkladenykhShliakhakh() {
        // GET /shipments/{id} — деталі, власник стану shipment-service.
        assertThat(RouteTable.match(HttpMethod.GET, "/api/v1/shipments/abc")
                        .orElseThrow()
                        .service())
                .isEqualTo("shipment");
        assertThat(RouteTable.match(HttpMethod.GET, "/api/v1/shipments")
                        .orElseThrow()
                        .id())
                .isEqualTo("shipments-dashboard");
    }

    @Test
    void idMarshrutivUnikalni() {
        Set<String> ids = new HashSet<>();
        RouteTable.ROUTES.forEach(r -> assertThat(ids.add(r.id())).as(r.id()).isTrue());
    }
}
