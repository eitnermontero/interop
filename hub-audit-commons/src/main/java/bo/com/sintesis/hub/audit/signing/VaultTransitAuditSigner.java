package bo.com.sintesis.hub.audit.signing;

import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.Signature;

import java.nio.charset.StandardCharsets;

/**
 * Implementación de {@link AuditSigner} que firma el {@code chain_hash}
 * usando la operación {@code sign} de Vault Transit.
 *
 * <p>La clave utilizada es configurable mediante la propiedad
 * {@code audit.integrity.vault-key-name} (default {@code hub-audit-signing}).
 *
 * <p>La firma resultante se almacena en formato base64url tal como la devuelve
 * Vault (incluye el prefijo {@code vault:v<N>:} que contiene la versión de clave).
 * La versión de clave también se almacena de forma independiente en la columna
 * {@code key_version} del registro de auditoría para facilitar la verificación.
 *
 * <p>Lanza {@link AuditSigningException} (RuntimeException) ante cualquier fallo
 * de Vault para permitir el rollback transaccional automático.
 */
public final class VaultTransitAuditSigner implements AuditSigner {

    private final VaultTransitOperations vaultTransit;
    private final String keyName;

    public VaultTransitAuditSigner(VaultTransitOperations vaultTransit, String keyName) {
        if (vaultTransit == null) {
            throw new IllegalArgumentException("VaultTransitOperations no puede ser nulo");
        }
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("El nombre de la clave Vault no puede ser nulo o vacío");
        }
        this.vaultTransit = vaultTransit;
        this.keyName = keyName;
    }

    @Override
    public SignResult sign(String chainHash) {
        if (chainHash == null || chainHash.isBlank()) {
            throw new AuditSigningException("El chain_hash no puede ser nulo o vacío para firmar");
        }
        try {
            Plaintext plaintext = Plaintext.of(chainHash.getBytes(StandardCharsets.UTF_8));
            Signature vaultSignature = vaultTransit.sign(keyName, plaintext);
            String rawSignature = vaultSignature.getSignature();

            // El formato de Vault es "vault:v<version>:<base64url>"
            // Extraer la versión del prefijo para almacenarla en key_version
            int keyVersion = extractKeyVersion(rawSignature);
            return new SignResult(rawSignature, keyVersion);
        } catch (AuditSigningException e) {
            throw e;
        } catch (Exception e) {
            throw new AuditSigningException(
                    "Error al firmar el chain_hash con Vault Transit (clave='" + keyName + "'): " + e.getMessage(),
                    e);
        }
    }

    /**
     * Extrae la versión numérica de la clave del prefijo de firma de Vault.
     * El formato es {@code vault:v<N>:<payload>}, por ejemplo {@code vault:v2:AAAA...}.
     *
     * @param signature firma en formato Vault
     * @return versión de la clave; {@code 0} si no se puede determinar
     */
    private static int extractKeyVersion(String signature) {
        if (signature == null || !signature.startsWith("vault:v")) {
            return 0;
        }
        try {
            int start = "vault:v".length();
            int end = signature.indexOf(':', start);
            if (end < 0) {
                return 0;
            }
            return Integer.parseInt(signature.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
