package bo.com.sintesis.hub.audit;

import lombok.RequiredArgsConstructor;

/**
 * Default sink: delegates to AuditClient for HTTP delivery to admin-service.
 * Active when audit.sink-mode=remote (or absent).
 */
@RequiredArgsConstructor
public class RemoteHttpSink implements AuditEventSink {

    private final AuditClient client;

    @Override
    public void emit(AuditEventDto event) {
        client.send(event);
    }
}
