package bo.com.sintesis.hub.audit.hub;

/**
 * Excepción lanzada cuando se intenta registrar un evento de auditoría
 * con una {@code idempotency_key} que ya existe en la tabla
 * {@code public.hub_audit_idempotency}.
 *
 * <p>Al ser una {@link RuntimeException}, provoca el rollback automático
 * de la transacción en curso, garantizando que ni el registro de auditoría
 * ni el evento de outbox se persistan para una clave duplicada.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("La clave de idempotencia ya fue procesada: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Retorna la clave de idempotencia que generó el conflicto.
     *
     * @return clave de idempotencia duplicada
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
