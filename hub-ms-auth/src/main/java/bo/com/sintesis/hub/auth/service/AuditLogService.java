package bo.com.sintesis.hub.auth.service;

import bo.com.sintesis.hub.auth.domain.AuditLog;
import bo.com.sintesis.hub.auth.repository.AuditLogRepository;
import bo.com.sintesis.hub.auth.service.dto.AuditEventRequest;
import bo.com.sintesis.hub.auth.service.dto.AuditLogDto;
import bo.com.sintesis.hub.auth.service.dto.AuditLogFilter;
import bo.com.sintesis.hub.auth.service.dto.KeycloakEventRequest;
import bo.com.sintesis.hub.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.hub.auth.web.rest.errors.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public AuditLogDto record(AuditEventRequest req) {
        AuditLog entity = toEntity(req);
        return toDto(auditLogRepository.save(entity));
    }

    @Transactional
    public void recordFromKeycloak(KeycloakEventRequest event) {
        if (event.type() == null) {
            throw new AdminApiException(ErrorCode.VALIDATION_ERROR,
                "Keycloak event missing 'type'");
        }
        AuditLog entity = new AuditLog();
        entity.setEventTime(event.time() == null ? Instant.now()
            : Instant.ofEpochMilli(event.time()));
        entity.setEventType(mapKeycloakType(event.type()));
        entity.setModule("AUTH");
        entity.setOptionCode("SESION");
        entity.setUserId(event.userId());
        entity.setIpAddress(event.ipAddress());
        entity.setServiceName("keycloak");
        if (event.details() != null) {
            entity.setUsername(event.details().get("username"));
            Map<String, Object> details = new HashMap<>(event.details());
            if (event.error() != null) details.put("error", event.error());
            if (event.clientId() != null) details.put("clientId", event.clientId());
            if (event.sessionId() != null) details.put("sessionId", event.sessionId());
            entity.setDetails(details);
        }
        auditLogRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(AuditLogFilter filter, Pageable pageable) {
        return auditLogRepository.findAll(buildSpec(filter), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public AuditLogDto getById(Long id) {
        return auditLogRepository.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new AdminApiException(ErrorCode.AUDIT_LOG_NOT_FOUND,
                "Audit log not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<String> distinctEventTypes() {
        return auditLogRepository.findDistinctEventTypes();
    }

    @Transactional(readOnly = true)
    public List<String> distinctModules() {
        return auditLogRepository.findDistinctModules();
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findAllForExport(AuditLogFilter filter, int maxRows) {
        Pageable page = org.springframework.data.domain.PageRequest.of(0, maxRows,
            org.springframework.data.domain.Sort.by("eventTime").descending());
        return auditLogRepository.findAll(buildSpec(filter), page).getContent();
    }

    // -- Internals -----------------------------------------------------------

    private Specification<AuditLog> buildSpec(AuditLogFilter f) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (f.from() != null) preds.add(cb.greaterThanOrEqualTo(root.get("eventTime"), f.from()));
            if (f.to()   != null) preds.add(cb.lessThanOrEqualTo(root.get("eventTime"), f.to()));
            if (notBlank(f.username())) preds.add(cb.equal(root.get("username"), f.username()));
            if (notBlank(f.userId()))   preds.add(cb.equal(root.get("userId"), f.userId()));
            if (notBlank(f.serviceName())) preds.add(cb.equal(root.get("serviceName"), f.serviceName()));
            if (notBlank(f.ipAddress())) preds.add(cb.equal(root.get("ipAddress"), f.ipAddress()));
            if (f.eventTypes() != null && !f.eventTypes().isEmpty()) {
                preds.add(root.get("eventType").in(f.eventTypes()));
            }
            if (f.modules() != null && !f.modules().isEmpty()) {
                preds.add(root.get("module").in(f.modules()));
            }
            if (f.responseStatuses() != null && !f.responseStatuses().isEmpty()) {
                preds.add(root.get("responseStatus").in(f.responseStatuses()));
            }
            if (notBlank(f.q())) {
                String like = "%" + f.q().toLowerCase() + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("endpoint")), like),
                    cb.like(cb.lower(root.get("optionCode")), like),
                    cb.like(cb.lower(root.get("fullName")), like)
                ));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String mapKeycloakType(String kcType) {
        return switch (kcType) {
            case "LOGIN", "CODE_TO_TOKEN" -> "LOGIN";
            case "LOGIN_ERROR"            -> "LOGIN_ERROR";
            case "LOGOUT"                 -> "LOGOUT";
            case "LOGOUT_ERROR"           -> "LOGOUT";
            case "REFRESH_TOKEN"          -> "TOKEN_REFRESH";
            case "REFRESH_TOKEN_ERROR"    -> "TOKEN_REFRESH";
            default                       -> kcType;
        };
    }

    private AuditLog toEntity(AuditEventRequest r) {
        AuditLog a = new AuditLog();
        a.setEventTime(r.eventTime());
        a.setEventType(r.eventType());
        a.setModule(r.module());
        a.setOptionCode(r.optionCode());
        a.setUserId(r.userId());
        a.setUsername(r.username());
        a.setFullName(r.fullName());
        a.setRoles(r.roles() == null ? new String[0] : r.roles().toArray(String[]::new));
        a.setIpAddress(r.ipAddress());
        a.setUserAgent(r.userAgent());
        a.setServiceName(r.serviceName());
        a.setHttpMethod(r.httpMethod());
        a.setEndpoint(r.endpoint());
        a.setResponseStatus(r.responseStatus());
        a.setDurationMs(r.durationMs());
        a.setDetails(r.details());
        a.setTenantId(r.tenantId());
        a.setReqId(r.reqId());
        return a;
    }

    private AuditLogDto toDto(AuditLog a) {
        return new AuditLogDto(
            a.getId(), a.getEventTime(), a.getEventType(), a.getModule(),
            a.getOptionCode(), a.getUserId(), a.getUsername(), a.getFullName(),
            a.getRoles() == null ? List.of() : List.of(a.getRoles()),
            a.getIpAddress(), a.getUserAgent(), a.getServiceName(),
            a.getHttpMethod(), a.getEndpoint(), a.getResponseStatus(),
            a.getDurationMs(), a.getDetails(), a.getTenantId(), a.getReqId()
        );
    }
}
