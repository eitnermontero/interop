package bo.com.sintesis.mdqr.auth.service.dto;

import java.time.Instant;
import java.util.List;

public record AuditLogFilter(
    Instant from,
    Instant to,
    String username,
    String userId,
    List<String> eventTypes,
    List<String> modules,
    String serviceName,
    String ipAddress,
    List<Integer> responseStatuses,
    String q
) {}
