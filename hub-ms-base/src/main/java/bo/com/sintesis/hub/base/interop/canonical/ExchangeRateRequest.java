package bo.com.sintesis.hub.base.interop.canonical;

import java.time.LocalDate;

/**
 * Contrato canónico de entrada para consultas de tipo de cambio.
 * Este es el único contrato que las aplicaciones internas conocen;
 * la traducción al contrato del proveedor es responsabilidad del adaptador.
 *
 * @param baseCurrency   código ISO 4217 de la moneda origen (p.ej. "BOB")
 * @param targetCurrency código ISO 4217 de la moneda destino (p.ej. "UFV")
 * @param date           fecha para la que se solicita el tipo de cambio
 * @param idempotencyKey clave de idempotencia del caller; puede ser {@code null}
 *                       si el caller no necesita garantías de exactly-once
 */
public record ExchangeRateRequest(
        String baseCurrency,
        String targetCurrency,
        LocalDate date,
        String idempotencyKey
) {
}
