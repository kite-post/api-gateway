package ua.kite.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Шлюз цілком: справжній Redis (Testcontainers), JWT, підписані власним ключем, і фейковий сервіс
 * на JDK HttpServer, який віддає ще й JWKS — так само, як Keycloak віддає його на стенді.
 *
 * <p>Критерій задачі: запит без токена відхиляється, з валідним — доходить до сервіса.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

    static final String ISSUER = "http://localhost:8081/realms/kite";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static final RSAKey KEY = generateKey("kite-test");
    static final RSAKey FOREIGN_KEY = generateKey("kite-test");

    /** Останній запит, що дійшов до сервіса, за шляхом. */
    static final Map<String, HttpExchangeRecord> RECEIVED = new ConcurrentHashMap<>();

    static final HttpServer UPSTREAM = startUpstream();

    record HttpExchangeRecord(String method, String authorization, String requestId) {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String upstream = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> upstream + "/certs");
        for (String service : List.of(
                "customer",
                "business",
                "shipment",
                "pricing",
                "network",
                "logistics",
                "tracking",
                "payment",
                "care",
                "scan",
                "realtime",
                "locker")) {
            registry.add("kite.gateway.services." + service, () -> upstream);
        }
        // audit «лежить»: порт, на якому ніхто не слухає.
        registry.add("kite.gateway.services.audit", () -> "http://127.0.0.1:1");
        // Ліміт входу в тесті: два запити, далі 429 на хвилину.
        registry.add("kite.gateway.rate-limits.auth.replenish-rate", () -> 1);
        registry.add("kite.gateway.rate-limits.auth.burst-capacity", () -> 120);
        registry.add("kite.gateway.rate-limits.auth.requested-tokens", () -> 60);
        // Breaker відкривається після двох збоїв поспіль — щоб тест не робив двадцять запитів.
        registry.add("kite.gateway.circuit-breaker.sliding-window-size", () -> 2);
        registry.add("kite.gateway.circuit-breaker.minimum-number-of-calls", () -> 2);
    }

    @Autowired
    WebTestClient web;

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    // ── Критерій готовності ────────────────────────────────────────────────

    @Test
    void bezTokenaVidkhyliaietsia() {
        web.get()
                .uri("/api/v1/shipments?direction=incoming")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .valueEquals("WWW-Authenticate", "Bearer")
                .expectHeader()
                .exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(401)
                .jsonPath("$.code")
                .isEqualTo("AUTH_REQUIRED")
                .jsonPath("$.requestId")
                .isNotEmpty();
    }

    @Test
    void zValidnymTokenomDokhodytDoServisa() {
        String token = token(KEY, "kite-api", Instant.now().plusSeconds(300));

        web.get()
                .uri("/api/v1/shipments/s-1")
                .headers(h -> h.setBearerAuth(token))
                .header("X-Request-Id", "req-42")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Request-Id", "req-42")
                .expectBody()
                .jsonPath("$.path")
                .isEqualTo("/api/v1/shipments/s-1");

        HttpExchangeRecord received = RECEIVED.get("/api/v1/shipments/s-1");
        assertThat(received).as("запит мав дійти до shipment-service").isNotNull();
        assertThat(received.authorization()).isEqualTo("Bearer " + token);
        assertThat(received.requestId()).isEqualTo("req-42");
    }

    // ── Перевірка JWT ──────────────────────────────────────────────────────

    @Test
    void smittiaZamistTokenaVidkhyliaietsia() {
        expectInvalidToken("not-a-jwt");
    }

    @Test
    void chuzhyiPidpysVidkhyliaietsia() {
        expectInvalidToken(token(FOREIGN_KEY, "kite-api", Instant.now().plusSeconds(300)));
    }

    @Test
    void prostrochenyiTokenVidkhyliaietsia() {
        expectInvalidToken(token(KEY, "kite-api", Instant.now().minusSeconds(600)));
    }

    @Test
    void tokenNeDliaKiteApiVidkhyliaietsia() {
        expectInvalidToken(token(KEY, "account", Instant.now().plusSeconds(300)));
    }

    @Test
    void publichnyiShliakhBezTokena() {
        web.get()
                .uri("/api/v1/public/track/KT123456785UA")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void publichnyiShliakhIgnoruieProstrochenyiToken() {
        String expired = token(KEY, "kite-api", Instant.now().minusSeconds(600));
        web.get()
                .uri("/api/v1/public/locations")
                .headers(h -> h.setBearerAuth(expired))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void nevidomyiShliakhBezTokenaTezh401() {
        web.get().uri("/api/v1/nothing-here").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void nevidomyiShliakhZTokenom404() {
        web.get()
                .uri("/api/v1/nothing-here")
                .headers(h ->
                        h.setBearerAuth(token(KEY, "kite-api", Instant.now().plusSeconds(300))))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // ── Тайм-аут, circuit breaker, rate limit ──────────────────────────────

    @Test
    void povilnyiServisDaie504ZaDviSekundy() {
        Instant start = Instant.now();
        web.mutate()
                .responseTimeout(Duration.ofSeconds(10))
                .build()
                .get()
                .uri("/api/v1/payments/slow")
                .headers(h ->
                        h.setBearerAuth(token(KEY, "kite-api", Instant.now().plusSeconds(300))))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UPSTREAM_TIMEOUT")
                .jsonPath("$.instance")
                .isEqualTo("/api/v1/payments/slow");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(2900));
    }

    @Test
    void nedostupnyiServisVidkryvaieBreaker() {
        String token = token(KEY, "kite-api", Instant.now().plusSeconds(300));
        for (int i = 0; i < 2; i++) {
            web.get()
                    .uri("/api/v1/ops/audit")
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("UPSTREAM_UNAVAILABLE");
        }
        web.get()
                .uri("/api/v1/ops/audit")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UPSTREAM_CIRCUIT_OPEN");
    }

    @Test
    void vkhidObmezhenyiLimitom() {
        for (int i = 0; i < 2; i++) {
            web.post().uri("/api/v1/auth/otp").exchange().expectStatus().isOk();
        }
        web.post()
                .uri("/api/v1/auth/otp")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .exists("Retry-After")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("RATE_LIMITED");
    }

    // ── Допоміжне ──────────────────────────────────────────────────────────

    private void expectInvalidToken(String token) {
        web.get()
                .uri("/api/v1/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTH_INVALID_TOKEN");
    }

    static String token(RSAKey key, String audience, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject("00000000-0000-0000-0000-000000000001")
                    .audience(audience)
                    .issueTime(Date.from(expiresAt.minusSeconds(300)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("realm_access", Map.of("roles", List.of("CLIENT")))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/certs", ex -> respond(ex, new JWKSet(KEY.toPublicJWK()).toString()));
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                RECEIVED.put(
                        path,
                        new HttpExchangeRecord(
                                ex.getRequestMethod(),
                                ex.getRequestHeaders().getFirst("Authorization"),
                                ex.getRequestHeaders().getFirst("X-Request-Id")));
                if (path.endsWith("/slow")) {
                    sleep(Duration.ofSeconds(4));
                }
                respond(ex, "{\"path\":\"" + path + "\"}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException ignored) {
            // Шлюз уже відпустив з'єднання по тайм-ауту — для тесту це очікувано.
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
