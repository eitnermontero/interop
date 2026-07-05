package bo.com.sintesis.hub.auth.web.rest;

import bo.com.sintesis.hub.auth.config.ApplicationProperties;
import bo.com.sintesis.hub.auth.service.AuditLogService;
import bo.com.sintesis.hub.auth.service.dto.AuditEventRequest;
import bo.com.sintesis.hub.auth.service.dto.AuditLogDto;
import bo.com.sintesis.hub.auth.service.dto.KeycloakEventRequest;
import bo.com.sintesis.hub.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.hub.auth.web.rest.errors.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditIngestController {

    private static final String KEYCLOAK_SECRET_HEADER = "X-Keycloak-Secret";

    private final AuditLogService auditLogService;
    private final ApplicationProperties props;

    /**
     * Audit event ingestion from internal services (mwc-cart, mwc-report, mwc-soboce, mwc-gateway).
     * Authenticated via a Keycloak service-account JWT carrying the audit:write scope.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_audit:write')")
    public ResponseEntity<AuditLogDto> ingest(
            @Valid @RequestBody AuditEventRequest req) {
        AuditLogDto saved = auditLogService.record(req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
    }

    /**
     * Keycloak Event Listener webhook for LOGIN / LOGOUT / REFRESH_TOKEN events.
     * Authentication is a separate shared secret because the listener runs inside Keycloak.
     */
    @PostMapping("/keycloak")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingestKeycloak(
            @RequestHeader(value = KEYCLOAK_SECRET_HEADER, required = false) String secret,
            @RequestBody KeycloakEventRequest event) {
        validateKeycloakSecret(secret);
        auditLogService.recordFromKeycloak(event);
    }

    private void validateKeycloakSecret(String provided) {
        String expected = props.audit() == null ? null : props.audit().keycloakSecret();
        if (expected == null || expected.isBlank()) {
            throw new AdminApiException(ErrorCode.AUDIT_INGEST_UNAUTHORIZED,
                "Keycloak webhook secret not configured");
        }
        if (provided == null || !constantTimeEquals(expected, provided)) {
            throw new AdminApiException(ErrorCode.AUDIT_INGEST_UNAUTHORIZED,
                "Invalid or missing " + KEYCLOAK_SECRET_HEADER);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
