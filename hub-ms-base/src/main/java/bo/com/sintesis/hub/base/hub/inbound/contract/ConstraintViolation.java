package bo.com.sintesis.hub.base.hub.inbound.contract;

/**
 * Violación de contrato detectada por {@link ContractValidator}.
 *
 * @param field   nombre del campo que incumple la regla (snake_case)
 * @param message descripción legible del incumplimiento
 */
public record ConstraintViolation(String field, String message) {}
