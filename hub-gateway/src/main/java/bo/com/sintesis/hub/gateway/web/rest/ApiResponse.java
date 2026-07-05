package bo.com.sintesis.hub.gateway.web.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Envoltorio genérico de respuesta HTTP del hub de interoperabilidad (ADR-0005).
 *
 * <p>Todos los endpoints y todas las capas del stack inbound del hub retornan
 * esta estructura, tanto en éxito como en error (de negocio, de seguridad o de
 * infraestructura). El discriminante para el partner es siempre el campo
 * {@code success}.
 *
 * <p>Los campos {@code data} y {@code error} son mutuamente excluyentes:
 * en éxito se incluye {@code data} y {@code error} es {@code null}; en error
 * se incluye {@code error} y {@code data} es {@code null}.
 * {@code @JsonInclude(NON_NULL)} evita serializar los campos nulos para
 * mantener responses compactos.
 *
 * @param <T>           tipo del campo {@code data}
 * @param success       {@code true} si la operación fue exitosa
 * @param status        código HTTP semántico del resultado
 * @param message       mensaje legible en español resumiendo el resultado
 * @param data          datos del negocio; {@code null} en respuestas de error
 * @param error         detalle estructurado del error; {@code null} en éxito
 * @param correlationId UUID de trazabilidad; igual a {@code hub_audit_log.correlation_id}
 *                      y al header {@code X-Correlation-ID}
 * @param timestamp     instante ISO 8601 con offset de zona horaria (UTC)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        int status,
        String message,
        T data,
        ApiError error,
        String correlationId,
        String timestamp
) {

    /** Construye una respuesta exitosa. */
    public static <T> ApiResponse<T> ok(int status, String message, T data, String correlationId) {
        return new ApiResponse<>(true, status, message, data, null, correlationId, ahora());
    }

    /** Construye una respuesta de error. */
    public static <T> ApiResponse<T> error(int status, String message, ApiError error,
                                            String correlationId) {
        return new ApiResponse<>(false, status, message, null, error, correlationId, ahora());
    }

    private static String ahora() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
