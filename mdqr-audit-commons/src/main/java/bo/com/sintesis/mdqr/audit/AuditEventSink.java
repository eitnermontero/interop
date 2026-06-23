package bo.com.sintesis.mdqr.audit;

/**
 * Seam between the in-process buffer/retry loop and the actual transport.
 * Implementations: RemoteHttpSink (default), InProcessSink (admin-service), RedisStreamSink (future).
 */
public interface AuditEventSink {

    /**
     * Delivers one event to the destination.
     * Called by the publisher worker thread; may throw - the publisher retries on exception.
     */
    void emit(AuditEventDto event);
}
