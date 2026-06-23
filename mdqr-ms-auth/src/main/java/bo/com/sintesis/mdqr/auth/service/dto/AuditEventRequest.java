package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AuditEventRequest(
    @NotNull Instant eventTime,
    @NotBlank @Size(max = 50)  String eventType,
    @NotBlank @Size(max = 100) String module,
    @Size(max = 100) String optionCode,
    @Size(max = 100) String userId,
    @Size(max = 100) String username,
    @Size(max = 200) String fullName,
    List<String> roles,
    @Size(max = 50)  String ipAddress,
    @Size(max = 500) String userAgent,
    @NotBlank @Size(max = 50) String serviceName,
    @Size(max = 10)  String httpMethod,
    @Size(max = 255) String endpoint,
    Integer responseStatus,
    Integer durationMs,
    Map<String, Object> details,
    @Size(max = 50)  String tenantId,
    @Size(max = 128) String reqId
) {}
