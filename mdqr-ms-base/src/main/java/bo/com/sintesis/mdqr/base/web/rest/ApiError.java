package bo.com.sintesis.mdqr.base.web.rest;

import java.util.List;

/**
 * Detalle de error estructurado dentro de {@link ApiResponse} (ADR-0005).
 *
 * @param code       código de error máquina (ej. {@code "VALIDATION_ERROR"}, {@code "PRODUCT_NOT_AUTHORIZED"})
 * @param detail     descripción legible del error
 * @param violations lista de violaciones de contrato; vacía si el error no es de validación
 */
public record ApiError(
        String code,
        String detail,
        List<ApiViolation> violations
) {}
