package ua.kite.apigateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Таблиця маршрутів проти ендпоїнтів із 9.2–9.5: кожен шлях іде в сервіс, що ним володіє (5.1). */
class RouteTableTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "/api/v1/auth/otp, customer",
        "/api/v1/auth/otp/verify, customer",
        "/api/v1/auth/refresh, customer",
        "/api/v1/public/track/KT123456785UA, tracking",
        "/api/v1/public/locations, network",
        "/api/v1/public/locations/42, network",
        "/api/v1/public/quotes, pricing",
        "/api/v1/me, customer",
        "/api/v1/me/phones/1/verify, customer",
        "/api/v1/me/bonuses, payment",
        "/api/v1/me/external-tracking, tracking",
        "/api/v1/me/external-tracking/7, tracking",
        "/api/v1/shipments, shipment",
        "/api/v1/shipments/abc, shipment",
        "/api/v1/shipments/abc/confirm, shipment",
        "/api/v1/shipments/abc/actions/redirect, shipment",
        "/api/v1/shipments/abc/timeline, tracking",
        "/api/v1/payments, payment",
        "/api/v1/payments/p1, payment",
        "/api/v1/promo-codes/validate, pricing",
        "/api/v1/lockers/loc-1/open, locker",
        "/api/v1/storage-bookings, locker",
        "/api/v1/claims, care",
        "/api/v1/claims/c1/attachments, care",
        "/api/v1/tickets/t1/messages, care",
        "/api/v1/business/org, business",
        "/api/v1/business/webhooks/w1/test, business",
        "/api/v1/scans, scan",
        "/api/v1/ops/hubs/h1/sort-hint/KT123456785UA, scan",
        "/api/v1/scanner/pair, realtime",
        "/api/v1/ops/scanner-sessions, realtime",
        "/api/v1/ops/shipments/search, shipment",
        "/api/v1/ops/acceptances, shipment",
        "/api/v1/ops/handovers, shipment",
        "/api/v1/ops/units/u1/close, logistics",
        "/api/v1/ops/trips/t1/depart, logistics",
        "/api/v1/ops/discrepancies, logistics",
        "/api/v1/ops/locations, network",
        "/api/v1/ops/routing-rules, network",
        "/api/v1/ops/tariffs, pricing",
        "/api/v1/ops/promo-codes, pricing",
        "/api/v1/ops/users, customer",
        "/api/v1/ops/claims, care",
        "/api/v1/ops/audit, audit",
    })
    void shliakhIdeVSvoiServis(String path, String service) {
        assertThat(RouteTable.match(path))
                .as(path)
                .hasValueSatisfying(route -> assertThat(route.service()).isEqualTo(service));
    }

    @Test
    void lyshVkhidMaieSuvorishyiLimit() {
        assertThat(RouteTable.match("/api/v1/auth/otp").orElseThrow().bucket()).isEqualTo(RouteTable.AUTH_BUCKET);
        assertThat(RouteTable.match("/api/v1/public/quotes").orElseThrow().bucket())
                .isEqualTo(RouteTable.DEFAULT_BUCKET);
    }

    @Test
    void nevidomyiShliakhNikudyNeIde() {
        assertThat(RouteTable.match("/api/v1/unknown")).isEmpty();
        assertThat(RouteTable.match("/actuator/health")).isEmpty();
        assertThat(RouteTable.match("/api/v1/shipmentsX")).isEmpty();
    }

    @Test
    void idMarshrutivUnikalni() {
        Set<String> ids = new HashSet<>();
        RouteTable.ROUTES.forEach(r -> assertThat(ids.add(r.id())).as(r.id()).isTrue());
    }
}
