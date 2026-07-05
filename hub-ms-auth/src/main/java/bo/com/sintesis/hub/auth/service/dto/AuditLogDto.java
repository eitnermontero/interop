package bo.com.sintesis.hub.auth.service.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AuditLogDto(
    Long id,
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
