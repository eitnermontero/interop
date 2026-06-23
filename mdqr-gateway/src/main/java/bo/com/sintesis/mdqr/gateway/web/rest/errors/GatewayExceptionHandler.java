package bo.com.sintesis.mdqr.gateway.web.rest.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Maneja errores que el gateway encuentra al rutear al backend:
 * <ul>
 *   <li>{@link ConnectTimeoutException}, {@link ConnectException} → 502 Bad Gateway
 *       (backend caido o inalcanzable)</li>
 *   <li>{@link TimeoutException} → 504 Gateway Timeout</li>
 *   <li>{@link IOException} y resto de fallos de red → 502</li>
 *   <li>{@link ResponseStatusException} → se respeta su status</li>
 *   <li>cualquier otro → 500</li>
 * </ul>
 * <p>
 * Loguea UNA linea (`WARN` para 4xx, 5xx no-500; `ERROR` para 500) con el mensaje breve,
 * sin imprimir el stacktrace (que llena los logs cuando un backend esta down).
 * Para debug, setear el logger del filter a DEBUG.
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayExceptionHandler implements WebExceptionHandler {

    private static final String PROBLEM_BASE = "https://api.sintesis.com.bo/problems/";

    @Value("${spring.application.name:mdqr-gateway}")
    private String appName;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status;
        String errorCode;
        String detail;

        if (ex instanceof ConnectTimeoutException || ex instanceof ConnectException) {
            status = HttpStatus.BAD_GATEWAY;
            errorCode = "BAD_GATEWAY";
            detail = "Upstream service unavailable";
        } else if (ex instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            errorCode = "GATEWAY_TIMEOUT";
            detail = "Upstream service timed out";
        } else if (ex instanceof IOException) {
            status = HttpStatus.BAD_GATEWAY;
            errorCode = "BAD_GATEWAY";
            detail = "Upstream connection failed";
        } else if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            errorCode = status.name();
            detail = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "INTERNAL_SERVER_ERROR";
            detail = "Unexpected error";
        }

        String path = exchange.getRequest().getURI().getRawPath();
        String method = exchange.getRequest().getMethod().name();
        // Una sola linea — sin stacktrace.
        if (status.is5xxServerError() && status != HttpStatus.INTERNAL_SERVER_ERROR) {
            log.warn("{} {} -> {} {} ({})", method, path, status.value(), errorCode, ex.getMessage());
        } else if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("{} {} -> 500 INTERNAL_SERVER_ERROR ({})", method, path, ex.getMessage());
        } else {
            log.warn("{} {} -> {} {}", method, path, status.value(), errorCode);
        }

        return writeProblem(exchange, status, errorCode, detail, path);
    }

    private Mono<Void> writeProblem(ServerWebExchange exchange, HttpStatus status,
                                     String errorCode, String detail, String path) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        Map<String, Object> body = Map.of(
            "type", PROBLEM_BASE + errorCode.toLowerCase().replace('_', '-'),
            "title", toTitle(errorCode),
            "status", status.value(),
            "detail", detail,
            "errorCode", errorCode,
            "path", path,
            "timestamp", Instant.now().toString()
        );

        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"errorCode\":\"" + errorCode + "\"}").getBytes();
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static String toTitle(String code) {
        String lower = code.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
