package bo.com.sintesis.hub.base.web.rest;

/**
 * Violación de contrato serializable en la respuesta HTTP del hub (ADR-0005).
 *
 * @param field   nombre del campo que incumple la regla
 * @param message descripción legible del incumplimiento
 */
public record ApiViolation(String field, String message) {}
