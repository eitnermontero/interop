package bo.com.sintesis.mdqr.base.interop.canonical;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Contrato canónico de salida para consultas de tipo de cambio.
 * Las aplicaciones internas consumen únicamente este contrato,
 * independientemente del proveedor externo que lo resuelva.
 *
 * @param rate           valor del tipo de cambio con la precisión entregada por el proveedor
 * @param date           fecha de vigencia del tipo de cambio
 * @param sourceCurrency código ISO 4217 de la moneda origen
 * @param targetCurrency código ISO 4217 de la moneda destino
 * @param dataSource     fuente del dato (p.ej. "BCB", "SBEF")
 * @param retrievedAt    instante exacto en que el hub recibió la respuesta del proveedor
 */
public record ExchangeRateResponse(
        BigDecimal rate,
        LocalDate date,
        String sourceCurrency,
        String targetCurrency,
        String dataSource,
        Instant retrievedAt
) {
}
