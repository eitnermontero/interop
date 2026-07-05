package bo.com.sintesis.hub.gateway.web.rest.errors;

import bo.com.sintesis.hub.gateway.web.rest.ApiError;
import bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter;
import io.netty.channel.ConnectTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Manejador de excepciones de routing del gateway (ADR-0005 §6.1.c).
 *
 * <p>Intercepta las excepciones que genera Spring Cloud Gateway cuando no puede
 * completar el ruteo al backend y las convierte a {@link bo.com.sintesis.hub.gateway.web.rest.ApiResponse}
 * con los códigos canónicos del hub. Reemplaza el antiguo comportamiento que
 * emitía {@code application/problem+json}.
 *
 * <p>Mapeo de excepción → {@code error.code} / status HTTP (ADR-0005 §6.1.c):
 * <ul>
 *   <li>{@link ConnectException} / {@link ConnectTimeoutException} / sin instancias
 *       en Consul / circuit breaker abierto → {@code 503 SERVICE_UNAVAILABLE}</li>
 *   <li>{@link TimeoutException} → {@code 504 UPSTREAM_TIMEOUT}</li>
 *   <li>{@link IOException} / fallo de red genérico → {@code 502 UPSTREAM_ERROR}</li>
 *   <li>{@link ResponseStatusException} con su propio status → código derivado del status</li>
 *   <li>Cualquier otro → {@code 500 INTERNAL_ERROR}</li>
 * </ul>
 *
 * <p>El mapeo de {@link ConnectException} pasa de {@code 502 BAD_GATEWAY} (anterior)
 * a {@code 503 SERVICE_UNAVAILABLE} para alinearse con la semántica del ADR:
 * "no se pudo alcanzar el backend" ≠ "backend alcanzado pero respondió con error".
 *
 * <p>Respeta el check de {@code response.isCommitted()}: si la respuesta ya fue
 * comprometida (el backend comenzó a escribir antes de fallar), no puede
 * reescribirse el body.
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GatewayExceptionHandler implements WebExceptionHandler {

    private final ApiResponseWriter writer;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status;
        String errorCode;
        String detail;
        String message;

        if (ex instanceof ConnectTimeoutException || ex instanceof ConnectException) {
            // Backend inalcanzable o sin instancias en Consul / circuit breaker abierto
            status    = HttpStatus.SERVICE_UNAVAILABLE;
            errorCode = "SERVICE_UNAVAILABLE";
            message   = "Servicio temporalmente no disponible";
            detail    = "El servicio que atiende esta operación no está disponible en este momento. "
                        + "Reintente más tarde con la misma X-Idempotency-Key.";
        } else if (ex instanceof TimeoutException) {
            // El upstream no respondió dentro del timeout configurado
            status    = HttpStatus.GATEWAY_TIMEOUT;
            errorCode = "UPSTREAM_TIMEOUT";
            message   = "Tiempo de espera agotado";
            detail    = "El servicio upstream no respondió dentro del tiempo límite. "
                        + "Reintente con la misma X-Idempotency-Key.";
        } else if (ex instanceof IOException) {
            // Fallo de red genérico — backend alcanzado pero la conexión se rompió
            status    = HttpStatus.BAD_GATEWAY;
            errorCode = "UPSTREAM_ERROR";
            message   = "Error en el servicio upstream";
            detail    = "El servicio upstream respondió con un error. Reintente más tarde.";
        } else if (ex instanceof ResponseStatusException rse) {
            status  = HttpStatus.valueOf(rse.getStatusCode().value());
            errorCode = derivarCodigoDesdeStatus(status);
            message   = mensajeDesdeStatus(status);
            detail    = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
        } else {
            status    = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "INTERNAL_ERROR";
            message   = "Error interno del servidor";
            detail    = "Ocurrió un error inesperado. Reporte este incidente citando el correlation_id.";
        }

        String path   = exchange.getRequest().getURI().getRawPath();
        String method = exchange.getRequest().getMethod().name();

        // Una sola línea de log — sin stacktrace para no saturar logs cuando un backend está caído
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("{} {} → {} {} ({})", method, path, status.value(), errorCode, ex.getMessage());
        } else {
            log.warn("{} {} → {} {} ({})", method, path, status.value(), errorCode, ex.getMessage());
        }

        ApiError apiError = new ApiError(errorCode, detail, null);
        return writer.writeError(exchange, status, apiError, message);
    }

    /**
     * Deriva el {@code error.code} canónico a partir del status HTTP de una
     * {@link ResponseStatusException}.
     */
    private static String derivarCodigoDesdeStatus(HttpStatus status) {
        return switch (status.value()) {
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "INSUFFICIENT_SCOPE";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 502 -> "UPSTREAM_ERROR";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "UPSTREAM_TIMEOUT";
            default  -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INTERNAL_ERROR";
        };
    }

    private static String mensajeDesdeStatus(HttpStatus status) {
        return switch (status.value()) {
            case 401 -> "Autenticación requerida";
            case 403 -> "El token no autoriza el acceso a este recurso";
            case 404 -> "Recurso no encontrado";
            case 502 -> "Error en el servicio upstream";
            case 503 -> "Servicio temporalmente no disponible";
            case 504 -> "Tiempo de espera agotado";
            default  -> "Error interno del servidor";
        };
    }
}
