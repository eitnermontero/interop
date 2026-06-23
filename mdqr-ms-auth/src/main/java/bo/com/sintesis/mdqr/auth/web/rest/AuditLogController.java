package bo.com.sintesis.mdqr.auth.web.rest;

import bo.com.sintesis.mdqr.auth.config.ApplicationProperties;
import bo.com.sintesis.mdqr.auth.service.AuditLogExporter;
import bo.com.sintesis.mdqr.auth.service.AuditLogService;
import bo.com.sintesis.mdqr.auth.service.dto.AuditLogDto;
import bo.com.sintesis.mdqr.auth.service.dto.AuditLogFilter;
import bo.com.sintesis.mdqr.audit.Auditable;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Tag(name = "Auditoría", description = "Registro de operaciones administrativas")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AuditLogExporter auditLogExporter;
    private final ApplicationProperties props;

    @GetMapping
    @PreAuthorize("@permissionService.hasAction('AUDITORIA', 'READ')")
    public Page<AuditLogDto> search(
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) String username,
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) List<String> eventTypes,
        @RequestParam(required = false) List<String> modules,
        @RequestParam(required = false) String serviceName,
        @RequestParam(required = false) String ipAddress,
        @RequestParam(required = false) List<Integer> responseStatuses,
        @RequestParam(required = false) String q,
        @PageableDefault(size = 50)
        @SortDefault(sort = "eventTime", direction = org.springframework.data.domain.Sort.Direction.DESC)
        Pageable pageable
    ) {
        AuditLogFilter filter = new AuditLogFilter(
            from, to, username, userId, eventTypes, modules,
            serviceName, ipAddress, responseStatuses, q
        );
        return auditLogService.search(filter, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permissionService.hasAction('AUDITORIA', 'READ')")
    public AuditLogDto getById(@PathVariable Long id) {
        return auditLogService.getById(id);
    }

    @GetMapping("/event-types")
    @PreAuthorize("@permissionService.hasAction('AUDITORIA', 'READ')")
    public List<String> eventTypes() {
        return auditLogService.distinctEventTypes();
    }

    @GetMapping("/modules")
    @PreAuthorize("@permissionService.hasAction('AUDITORIA', 'READ')")
    public List<String> modules() {
        return auditLogService.distinctModules();
    }

    @GetMapping("/export")
    @Auditable(module = "AUDITORIA", option = "EXPORTAR_AUDITORIA", event = "EXPORT")
    @PreAuthorize("@permissionService.hasAction('AUDITORIA', 'EXPORT')")
    public ResponseEntity<byte[]> export(
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) String username,
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) List<String> eventTypes,
        @RequestParam(required = false) List<String> modules,
        @RequestParam(required = false) String serviceName,
        @RequestParam(required = false) String ipAddress,
        @RequestParam(required = false) List<Integer> responseStatuses,
        @RequestParam(required = false) String q
    ) {
        AuditLogFilter filter = new AuditLogFilter(
            from, to, username, userId, eventTypes, modules,
            serviceName, ipAddress, responseStatuses, q
        );
        int maxRows = props.audit() != null && props.audit().exportMaxRows() != null
            ? props.audit().exportMaxRows()
            : 50_000;
        byte[] body = auditLogExporter.toCsv(auditLogService.findAllForExport(filter, maxRows));

        String filename = "audit-" + DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            .replace(":", "-") + ".csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(body);
    }
}
