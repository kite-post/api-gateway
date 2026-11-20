package ua.kite.apigateway.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Відповіді шлюзу про помилки — у тому самому {@code application/problem+json} (RFC 9457), що й
 * у сервісів (9.1): з {@code code} і {@code requestId}, щоб клієнт не розрізняв, хто відмовив.
 */
@Component
public class Problems {

    private final ObjectMapper mapper;

    public Problems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String title, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://kite.ua/problems/" + code.toLowerCase().replace('_', '-'));
        body.put("title", title);
        body.put("status", status.value());
        if (detail != null) {
            body.put("detail", detail);
        }
        body.put("instance", RequestIdFilter.originalPath(exchange));
        body.put("code", code);
        String requestId = RequestIdFilter.requestId(exchange);
        if (requestId != null) {
            body.put("requestId", requestId);
        }

        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
