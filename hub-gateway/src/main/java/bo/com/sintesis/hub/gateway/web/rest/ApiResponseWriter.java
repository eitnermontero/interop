package bo.com.sintesis.hub.gateway.web.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static bo.com.sintesis.hub.gateway.web.filter.CorrelationIdFilter.ATTR_CORRELATION_ID;

/**
 * Componente auxiliar para serializar {@link ApiResponse} en la respuesta HTTP
 * reactiva del gateway.
 *
 * <p>Centraliza la escritura del body {@code application/json} con el
 * {@code correlation_id} correcto (leído del atributo del exchange, o generado
 * si falta — defensa en profundidad) y el header {@code X-Correlation-ID}.
 *
 * <p>Todos los puntos que emiten errores en el gateway (entry points, access
 * denied handler, filtros, exception handler) deben usar este componente para
 * garantizar la consistencia del contrato ADR-0005.
 */
@Component
public class ApiResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(ApiResponseWriter.class);

    /** Header de correlación escrito en todas las respuestas de error del gateway. */
    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Escribe un {@link ApiResponse} de error en el body de la respuesta.
     *
     * <p>El {@code correlationId} se obtiene del atributo
     * {@link CorrelationIdFilter#ATTR_CORRELATION_ID} del exchange. Si no está
     * disponible (el filtro de correlación no corrió aún, o se trata de un
     * rechazo muy temprano), se genera un UUID nuevo, se escribe en el atributo
     * y se registra en los logs para trazabilidad.
     *
     * @param exchange el exchange reactivo
     * @param status   código de estado HTTP a establecer
     * @param apiError detalle estructurado del error (código canónico + mensaje)
     * @param message  mensaje legible en español para el partner
     * @return {@code Mono<Void>} que completa al finalizar la escritura
     */
    public Mono<Void> writeError(ServerWebExchange exchange,
                                  HttpStatus status,
                                  ApiError apiError,
                                  String message) {

        String correlationId = resolverCorrelationId(exchange);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(
                MediaType.parseMediaType("application/json;charset=UTF-8"));
        exchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, correlationId);

        ApiResponse<Void> body = ApiResponse.error(status.value(), message, apiError, correlationId);

        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            log.error("Error al serializar ApiResponse: {}", e.getMessage());
            bytes = fallbackBytes(correlationId, apiError.code());
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * Resuelve el {@code correlationId} del exchange. Si el atributo no existe
     * (rechazo antes del filtro de correlación), genera un UUID nuevo y lo
     * registra en el exchange y en los logs.
     */
    private String resolverCorrelationId(ServerWebExchange exchange) {
        Object attr = exchange.getAttributes().get(ATTR_CORRELATION_ID);
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        String generado = UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTR_CORRELATION_ID, generado);
        log.warn("correlationId no encontrado en el exchange (rechazo temprano) — generado={}", generado);
        return generado;
    }

    /** Bytes de emergencia si falla la serialización Jackson (no debería ocurrir). */
    private static byte[] fallbackBytes(String correlationId, String errorCode) {
        String json = String.format(
                "{\"success\":false,\"status\":500,\"message\":\"Error interno\","
                        + "\"data\":null,\"error\":{\"code\":\"%s\",\"detail\":\"Error de serialización\"},"
                        + "\"correlation_id\":\"%s\",\"timestamp\":\"2000-01-01T00:00:00Z\"}",
                errorCode, correlationId);
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
