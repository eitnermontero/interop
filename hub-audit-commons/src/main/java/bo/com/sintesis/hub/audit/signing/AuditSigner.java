package bo.com.sintesis.hub.audit.signing;

/**
 * Contrato para la firma del {@code chain_hash} de un registro de auditoría.
 *
 * <p>Implementaciones disponibles:
 * <ul>
 *   <li>{@link VaultTransitAuditSigner} — firma real con Vault Transit
 *       (activada por {@code audit.integrity.vault-enabled=true}).</li>
 *   <li>{@link NoOpAuditSigner} — sin firma; usada cuando Vault no está
 *       disponible o la propiedad está deshabilitada.</li>
 * </ul>
 */
public interface AuditSigner {

    /**
     * Firma el {@code chain_hash} y devuelve la firma y la versión de clave usada.
     *
     * @param chainHash hex SHA-256 del chain_hash (64 caracteres en minúsculas)
     * @return resultado de la firma; nunca {@code null}
     * @throws AuditSigningException si la operación de firma falla
     */
    SignResult sign(String chainHash);
}
