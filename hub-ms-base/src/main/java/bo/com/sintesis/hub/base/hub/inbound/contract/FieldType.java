package bo.com.sintesis.hub.base.hub.inbound.contract;

/**
 * Tipos de campo admitidos en un contrato canónico inbound.
 *
 * <p>El {@link ContractValidator} usa estos valores para verificar que el valor
 * recibido en el payload sea del tipo correcto.
 */
public enum FieldType {

    /** Cadena de texto ({@link String}). */
    STRING,

    /** Número entero. Se acepta cualquier instancia de {@link Number} cuyo valor sea entero. */
    INTEGER,

    /** Booleano ({@link Boolean}). */
    BOOLEAN,

    /** Fecha/hora ISO 8601 con offset ({@link java.time.OffsetDateTime} como String en el payload). */
    DATETIME,

    /** Arreglo ({@link java.util.List}). */
    ARRAY
}
