package bo.com.sintesis.mdqr.audit.chain;

import java.time.Instant;

/**
 * Value object inmutable que encapsula los datos necesarios para calcular
 * el {@code chain_hash} de un registro de auditoría.
 *
 * @param partnerId     identificador único del partner (clave de la cadena)
 * @param requestHash   SHA-256 hex del payload canonicalizado de la petición (64 chars)
 * @param responseHash  SHA-256 hex del payload canonicalizado de la respuesta (64 chars)
 * @param timestamp     instante exacto de la transacción (se serializa como ISO-8601)
 * @param prevHash      SHA-256 hex del {@code chain_hash} del registro anterior del
 *                      mismo partner; para el primer registro usar
 *                      {@link ChainHashCalculator#GENESIS_PREV_HASH}
 */
public record ChainHashEntry(
        String partnerId,
        String requestHash,
        String responseHash,
        Instant timestamp,
        String prevHash
) {
}
