package bo.com.sintesis.mdqr.audit.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Calcula el hash SHA-256 de payloads de auditoría.
 *
 * <p>Para payloads JSON, aplica canonicalización RFC 8785 (JCS) antes de hashear,
 * lo que garantiza que payloads semánticamente equivalentes pero con distinto
 * orden de claves produzcan el mismo hash.
 *
 * <p>Para datos binarios no-JSON, aplica SHA-256 directamente sin canonicalización
 * (método {@link #hashRaw(byte[])}).
 *
 * <p>Todos los hashes se representan como cadenas hexadecimales en minúsculas
 * de exactamente 64 caracteres.
 */
public final class PayloadHasher {

    private static final String SHA_256 = "SHA-256";

    /**
     * Calcula el SHA-256 del payload JSON canonicalizado (RFC 8785).
     *
     * @param jsonPayload payload JSON como {@code String}
     * @return hex SHA-256 de 64 caracteres en minúsculas
     * @throws IllegalArgumentException si el payload no es JSON válido
     */
    public String hash(String jsonPayload) {
        byte[] canonical = JsonCanonicalizer.canonicalize(jsonPayload);
        return toHex(sha256(canonical));
    }

    /**
     * Calcula el SHA-256 de los bytes UTF-8 del payload JSON canonicalizado.
     *
     * @param rawBytes bytes UTF-8 del payload JSON
     * @return hex SHA-256 de 64 caracteres en minúsculas
     * @throws IllegalArgumentException si los bytes no representan JSON válido
     */
    public String hash(byte[] rawBytes) {
        byte[] canonical = JsonCanonicalizer.canonicalize(rawBytes);
        return toHex(sha256(canonical));
    }

    /**
     * Calcula el SHA-256 de datos binarios arbitrarios sin canonicalización.
     * Usar cuando el payload no es JSON (imágenes, binarios, etc.).
     *
     * @param bytes datos a hashear
     * @return hex SHA-256 de 64 caracteres en minúsculas
     */
    public String hashRaw(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Los bytes a hashear no pueden ser nulos o vacíos");
        }
        return toHex(sha256(bytes));
    }

    /**
     * Calcula el SHA-256 de un string usando la codificación UTF-8.
     * Método de utilidad para calcular hashes de strings no-JSON (p.ej. el hash génesis).
     *
     * @param value string a hashear
     * @return hex SHA-256 de 64 caracteres en minúsculas
     */
    public static String hashString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("El valor a hashear no puede ser nulo");
        }
        return toHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 está garantizado en todo JDK conforme a la especificación Java SE
            throw new IllegalStateException("SHA-256 no disponible en este JDK", e);
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
