package bo.com.sintesis.mdqr.base.hub.inbound.contract;

/**
 * Regla de validación para un campo individual del contrato canónico.
 *
 * @param field     nombre del campo en el payload (snake_case)
 * @param type      tipo de dato esperado
 * @param required  {@code true} si el campo es obligatorio
 * @param maxLength longitud máxima para campos {@link FieldType#STRING}; {@code null} = sin límite
 * @param format    formato adicional para campos {@link FieldType#DATETIME}: {@code "iso8601"};
 *                  {@code null} si no aplica
 */
public record FieldRule(
        String field,
        FieldType type,
        boolean required,
        Integer maxLength,
        String format
) {}
