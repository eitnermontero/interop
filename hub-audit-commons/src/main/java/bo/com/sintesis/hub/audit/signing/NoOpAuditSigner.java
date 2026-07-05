package bo.com.sintesis.hub.audit.signing;

/**
 * Implementación vacía de {@link AuditSigner} para cuando la firma con
 * Vault Transit está deshabilitada ({@code audit.integrity.vault-enabled=false}).
 *
 * <p>Devuelve siempre una {@link SignResult} con firma vacía y versión {@code 0}.
 * Los campos {@code signature} y {@code key_version} del registro de auditoría
 * quedarán vacío/cero respectivamente, lo que es válido según el esquema de BD
 * (ambas columnas permiten NULL).
 */
public final class NoOpAuditSigner implements AuditSigner {

    @Override
    public SignResult sign(String chainHash) {
        return new SignResult("", 0);
    }
}
