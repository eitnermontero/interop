package bo.com.sintesis.mdqr.auth.config;

import bo.com.sintesis.mdqr.auth.service.AuditLogService;
import bo.com.sintesis.mdqr.auth.service.dto.AuditEventRequest;
import bo.com.sintesis.mdqr.audit.AuditEventDto;
import bo.com.sintesis.mdqr.audit.AuditEventSink;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-process audit sink for mwc-admin-service.
 * Writes directly to AuditLogService, bypassing the HTTP ingest endpoint.
 * No ServiceTokenProvider or AuditClient are loaded in this mode.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "audit", name = "sink-mode", havingValue = "in-process")
public class InProcessSink implements AuditEventSink {

    private final AuditLogService auditLogService;

    @Override
    public void emit(AuditEventDto event) {
        auditLogService.record(toRequest(event));
    }

    private AuditEventRequest toRequest(AuditEventDto e) {
        return new AuditEventRequest(
            e.eventTime(),
            e.eventType(),
            e.module(),
            e.optionCode(),
            e.userId(),
            e.username(),
            e.fullName(),
            e.roles() == null ? List.of() : List.copyOf(e.roles()),
            e.ipAddress(),
            e.userAgent(),
            e.serviceName(),
            e.httpMethod(),
            e.endpoint(),
            e.responseStatus(),
            e.durationMs(),
            e.details(),
            e.tenantId(),
            e.reqId()
        );
    }
}
