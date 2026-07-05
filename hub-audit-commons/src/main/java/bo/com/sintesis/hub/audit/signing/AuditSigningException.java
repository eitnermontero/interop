package bo.com.sintesis.hub.audit.signing;

/**
 * Excepción lanzada cuando la operación de firma del {@code chain_hash}
 * con Vault Transit falla de forma irrecuperable.
 *
 * <p>Es una {@link RuntimeException} para no forzar bloques try-catch en el
 * {@code HubAuditService}, permitiendo que la transacción haga rollback
 * automáticamente si la firma falla.
 */
public class AuditSigningException extends RuntimeException {

    public AuditSigningException(String message) {
        super(message);
    }

    public AuditSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
