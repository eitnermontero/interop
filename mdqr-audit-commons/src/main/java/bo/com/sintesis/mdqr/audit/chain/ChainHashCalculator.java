package bo.com.sintesis.mdqr.audit.chain;

import bo.com.sintesis.mdqr.audit.hash.PayloadHasher;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * Calcula el {@code chain_hash} para un registro de auditoría del hub.
 *
 * <p>Fórmula:
 * <pre>
 *   chain_hash = SHA-256(prevHash || requestHash || responseHash || ts_iso8601 || partnerId)
 * </pre>
 * donde {@code ||} representa concatenación de strings UTF-8 sin separador.
 * El timestamp se serializa en formato ISO-8601 con offset UTC
 * (p.ej. {@code 2026-06-22T14:30:00Z}).
 *
 * <h2>Hash génesis</h2>
 * <p>El primer registro de cada partner no tiene registro anterior. En ese caso
 * se usa el hash génesis como {@code prevHash}:
 * <pre>
 *   GENESIS_PREV_HASH = SHA-256("HUB-INTEROP-GENESIS-MDQR-V1")
 *                     = 5fd411223e229226d47868c9e03b9436ab6945a13db493fdfc8e8b7a90860e33
 * </pre>
 */
public final class ChainHashCalculator {

    /**
     * Hash génesis fijo para el primer registro de cada partner.
     *
     * <p>Calculado como {@code SHA-256("HUB-INTEROP-GENESIS-MDQR-V1")} en UTF-8:
     * <pre>5fd411223e229226d47868c9e03b9436ab6945a13db493fdfc8e8b7a90860e33</pre>
     */
    public static final String GENESIS_PREV_HASH =
            "5fd411223e229226d47868c9e03b9436ab6945a13db493fdfc8e8b7a90860e33";

    /** Formato ISO-8601 con offset UTC para serializar el timestamp. */
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Calcula el {@code chain_hash} para la entrada dada.
     *
     * @param entry datos del registro de auditoría; ningún campo puede ser nulo
     * @return hex SHA-256 de 64 caracteres en minúsculas
     */
    public String calculate(ChainHashEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("La entrada de cadena no puede ser nula");
        }
        validateField("prevHash",     entry.prevHash());
        validateField("requestHash",  entry.requestHash());
        validateField("responseHash", entry.responseHash());
        validateField("partnerId",    entry.partnerId());
        if (entry.timestamp() == null) {
            throw new IllegalArgumentException("El timestamp no puede ser nulo");
        }

        String tsIso = TS_FORMATTER.format(entry.timestamp());

        String concatenated = entry.prevHash()
                + entry.requestHash()
                + entry.responseHash()
                + tsIso
                + entry.partnerId();

        byte[] input = concatenated.getBytes(StandardCharsets.UTF_8);
        return PayloadHasher.toHex(PayloadHasher.sha256(input));
    }

    private static void validateField(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El campo '" + name + "' no puede ser nulo o vacío");
        }
    }
}
