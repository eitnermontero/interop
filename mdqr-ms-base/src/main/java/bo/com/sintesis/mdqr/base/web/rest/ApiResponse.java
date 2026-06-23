package bo.com.sintesis.mdqr.base.web.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Envelope genérico de respuesta HTTP del hub de interoperabilidad (ADR-0005).
 *
 * <p>Todos los endpoints del motor inbound ({@code /api/inbound/**}) retornan
 * esta estructura. Los campos {@code data} y {@code error} son mutuamente excluyentes:
 * en éxito se incluye {@code data} y {@code error} es {@code null}; en error
 * se incluye {@code error} y {@code data} es {@code null}.
 *
 * <p>{@code @JsonInclude(NON_NULL)} evita serializar los campos nulos para
 * mantener responses compactos.
 *
 * @param <T>           tipo del campo {@code data}
 * @param success       {@code true} si la operación fue exitosa
 * @param status        código HTTP del resultado
 * @param message       mensaje legible resumiendo el resultado
 * @param data          datos del negocio; {@code null} en respuestas de error
 * @param error         detalle del error; {@code null} en respuestas exitosas
 * @param correlationId ID de correlación para trazabilidad distribuida
 * @param timestamp     instante ISO 8601 con offset de la respuesta
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
