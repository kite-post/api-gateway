package ua.kite.apigateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

import ua.kite.apigateway.routing.RouteTable;
import ua.kite.apigateway.web.Problems;

/**
 * Перевірка JWT від Keycloak (9.1): підпис за JWKS realm-у {@code kite}, {@code iss}, строк дії і
 * audience {@code kite-api} — з {@code spring.security.oauth2.resourceserver.jwt.*}. Публічні
 * шляхи з 9.2 пускаються без токена; решта {@code /api/v1} — лише з валідним. Ролі шлюз не
 * розбирає: токен іде в сервіс як є, і права за таблицею 9.5 перевіряє сам сервіс.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, Problems problems) {
        ServerWebExchangeMatcher publicPaths =
                ServerWebExchangeMatchers.pathMatchers(RouteTable.PUBLIC_PATHS.toArray(String[]::new));

        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .authorizeExchange(auth -> auth.matchers(publicPaths)
                        .permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(rs -> rs.bearerTokenConverter(ignoreTokenOn(publicPaths))
                        .authenticationEntryPoint(unauthorized(problems))
                        .accessDeniedHandler(forbidden(problems))
                        .jwt(jwt -> {}))
                .exceptionHandling(e ->
                        e.authenticationEntryPoint(unauthorized(problems)).accessDeniedHandler(forbidden(problems)))
                .build();
    }

    /**
     * На публічних шляхах токен не перевіряємо взагалі: інакше клієнт із простроченим токеном
     * отримав би 401 на {@code /auth/refresh} — саме там, де він цей токен і оновлює.
     */
    private static ServerAuthenticationConverter ignoreTokenOn(ServerWebExchangeMatcher publicPaths) {
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        return exchange -> publicPaths
                .matches(exchange)
                .flatMap(match -> match.isMatch() ? Mono.empty() : bearer.convert(exchange));
    }

    private static ServerAuthenticationEntryPoint unauthorized(Problems problems) {
        return (exchange, ex) -> {
            boolean invalid = ex instanceof InvalidBearerTokenException
                    || exchange.getRequest().getHeaders().containsKey("Authorization");
            exchange.getResponse()
                    .getHeaders()
                    .set("WWW-Authenticate", invalid ? "Bearer error=\"invalid_token\"" : "Bearer");
            return invalid
                    ? problems.write(
                            exchange,
                            HttpStatus.UNAUTHORIZED,
                            "AUTH_INVALID_TOKEN",
                            "Недійсний токен",
                            "Токен прострочений, підписаний не тим ключем або виданий не для kite-api.")
                    : problems.write(
                            exchange,
                            HttpStatus.UNAUTHORIZED,
                            "AUTH_REQUIRED",
                            "Потрібна автентифікація",
                            "Додайте заголовок Authorization: Bearer <JWT>.");
        };
    }

    private static ServerAccessDeniedHandler forbidden(Problems problems) {
        return (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Немає прав", null);
    }
}
