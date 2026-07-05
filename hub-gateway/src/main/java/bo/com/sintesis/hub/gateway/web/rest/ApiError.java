package bo.com.sintesis.hub.gateway.web.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Detalle de error estructurado dentro de {@link ApiResponse} (ADR-0005).
 *
 * <p>{@code violations} se omite de la serialización cuando es {@code null}
 * para mantener respuestas compactas en errores que no son de validación.
 *
 * @param code       código de error canónico en {@code SCREAMING_SNAKE_CASE}
 *                   (p. ej. {@code "AUTHENTICATION_REQUIRED"}, {@code "TOKEN_EXPIRED"})
 * @param detail     descripción legible orientada al consumidor; nunca contiene
 *                   stack traces ni detalles internos en perfil productivo
 * @param violations lista de violaciones de validación; {@code null} si el error
 *                   no es de tipo {@code VALIDATION_ERROR}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String detail,
        List<ApiViolation> violations
) {}
