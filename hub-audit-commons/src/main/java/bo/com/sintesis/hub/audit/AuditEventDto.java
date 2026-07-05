package bo.com.sintesis.hub.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload sent by the publisher to the admin-service ingest endpoint.
 * Field names match {@code AuditEventRequest} on the admin side.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventDto(
    Instant eventTime,
    String eventType,
    String module,
    String optionCode,
    String userId,
    String username,
    String fullName,
    List<String> roles,
    String ipAddress,
    String userAgent,
    String serviceName,
    String httpMethod,
    String endpoint,
    Integer responseStatus,
    Integer durationMs,
    Map<String, Object> details,
    String tenantId,
    String reqId
) {}
