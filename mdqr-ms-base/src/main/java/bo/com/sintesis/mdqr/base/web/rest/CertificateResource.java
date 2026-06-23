package bo.com.sintesis.mdqr.base.web.rest;

import bo.com.sintesis.mdqr.base.domain.Certificate;
import bo.com.sintesis.mdqr.base.service.CertificateService;
import bo.com.sintesis.mdqr.base.service.dto.*;
import bo.com.sintesis.mdqr.base.service.exception.DuplicateCertificateException;
import bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException;
import bo.com.sintesis.mdqr.base.service.mapper.CertificateMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller para gestión de certificados públicos.
 * <p>
 * Endpoints principales:
 * - GET /api/certificates: Listar certificados
 * - POST /api/certificates: Subir nuevo certificado
 * - GET /api/certificates/{id}: Ver detalle
 * - POST /api/certificates/{id}/activate: Activar
 * - POST /api/certificates/{id}/deactivate: Desactivar
 * - POST /api/certificates/{id}/revoke: Revocar
 * - POST /api/certificates/{id}/replace: Reemplazar con nueva versión
 * </p>
 */
@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Certificates", description = "APIs para gestión de certificados públicos de bancos")
@SecurityRequirement(name = "bearer-jwt")
public class CertificateResource {

    private final CertificateService certificateService;
    private final CertificateMapper certificateMapper;

    /**
     * GET /api/certificates : Lista todos los certificados activos.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(
        summary = "Listar certificados",
        description = "Lista todos los certificados activos (current version, no revocados) con información de estado y expiración."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Certificados listados exitosamente"),
        @ApiResponse(responseCode = "401", description = "No autenticado", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "No autorizado", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Page<CertificateDTO>> listCertificates(Pageable pageable) {
        log.info("GET /api/certificates - Listando certificados");

        Page<Certificate> certificates = certificateService.getCertificates(pageable);
        Page<CertificateDTO> dtos = certificates.map(certificateMapper::toDto);

        log.info("GET /api/certificates - {} certificados encontrados", certificates.getTotalElements());

        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(certificates.getTotalElements()))
            .body(dtos);
    }

    /**
     * POST /api/certificates : Sube un nuevo certificado público.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Subir certificado",
        description = "Sube un nuevo certificado público al sistema. El certificado debe estar en formato PEM."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Certificado subido exitosamente", content = @Content(schema = @Schema(implementation = CertificateDTO.class))),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o certificado PEM malformado", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Certificado duplicado", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "No autenticado", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "No autorizado", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CertificateDTO> uploadCertificate(
        @Valid @RequestBody UploadCertificateRequest request,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        log.info("POST /api/certificates - Subiendo certificado para entity: {}, user: {}, ip: {}",
                 request.getEntityId(), userId, ipAddress);

        try {
            Certificate certificate = certificateService.uploadCertificate(
                request.getPemContent(),
                request.getEntityId(),
                request.getEntityName(),
                request.getDescription(),
                request.getTags(),
                request.getNotificationEmails(),
                userId,
                null, // userEmail - obtener del principal si está disponible
                ipAddress
            );

            CertificateDTO dto = certificateMapper.toDto(certificate);

            log.info("POST /api/certificates - Certificado subido: ID={}, Serial={}",
                     certificate.getId(), certificate.getSerialNumber());

            return ResponseEntity
                .created(URI.create("/api/certificates/" + certificate.getId()))
                .body(dto);

        } catch (DuplicateCertificateException e) {
            log.warn("POST /api/certificates - Certificado duplicado: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("POST /api/certificates - Error: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error al subir certificado: " + e.getMessage());
        }
    }

    /**
     * POST /api/certificates/upload-file : Sube un certificado desde archivo.
     */
    @PostMapping("/upload-file")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Subir certificado desde archivo",
        description = "Sube un certificado desde un archivo (.crt, .pem, .cer, .txt)"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Certificado subido exitosamente"),
        @ApiResponse(responseCode = "400", description = "Archivo inválido"),
        @ApiResponse(responseCode = "409", description = "Certificado duplicado")
    })
    public ResponseEntity<CertificateDTO> uploadCertificateFromFile(
        @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
        @RequestParam("entityId") String entityId,
        @RequestParam("entityName") String entityName,
        @RequestParam(value = "description", required = false) String description,
        @RequestParam(value = "tags", required = false) String[] tags,
        @RequestParam(value = "notificationEmails", required = false) String[] notificationEmails,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        log.info("POST /api/certificates/upload-file - Subiendo desde archivo: {}, entity: {}, user: {}",
                 file.getOriginalFilename(), entityId, userId);

        try {
            // Leer contenido PEM del archivo
            String pemContent = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);

            Certificate certificate = certificateService.uploadCertificate(
                pemContent,
                entityId,
                entityName,
                description,
                tags,
                notificationEmails,
                userId,
                null,
                ipAddress
            );

            CertificateDTO dto = certificateMapper.toDto(certificate);

            log.info("POST /api/certificates/upload-file - Certificado subido: ID={}, Serial={}",
                     certificate.getId(), certificate.getSerialNumber());

            return ResponseEntity
                .created(URI.create("/api/certificates/" + certificate.getId()))
                .body(dto);

        } catch (DuplicateCertificateException e) {
            log.warn("POST /api/certificates/upload-file - Certificado duplicado: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("POST /api/certificates/upload-file - Error: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error al subir certificado: " + e.getMessage());
        }
    }

    /**
     * POST /api/certificates/validate : Valida un certificado sin guardarlo.
     */
    @PostMapping("/validate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(
        summary = "Validar certificado",
        description = "Valida un certificado PEM sin guardarlo en el sistema."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Certificado válido"),
        @ApiResponse(responseCode = "400", description = "Certificado inválido", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<?> validateCertificate(
        @Valid @RequestBody UploadCertificateRequest request,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        try {
            var metadata = certificateService.validateCertificate(
                request.getPemContent(),
                userId,
                null,
                ipAddress
            );

            return ResponseEntity.ok(metadata);
        } catch (Exception e) {
            log.warn("POST /api/certificates/validate - Certificado inválido: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Certificado inválido: " + e.getMessage());
        }
    }

    /**
     * GET /api/certificates/{id} : Obtiene detalle completo de un certificado.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(
        summary = "Obtener detalle de certificado",
        description = "Obtiene el detalle completo de un certificado incluyendo el contenido PEM."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Certificado encontrado", content = @Content(schema = @Schema(implementation = CertificateDetailDTO.class))),
        @ApiResponse(responseCode = "404", description = "Certificado no encontrado", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CertificateDetailDTO> getCertificate(@PathVariable Long id) {
        log.info("GET /api/certificates/{} - Obteniendo detalle", id);

        Certificate certificate = certificateService.getCertificateById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found: " + id));

        CertificateDetailDTO dto = certificateMapper.toDetailDto(certificate);

        return ResponseEntity.ok(dto);
    }

    /**
     * GET /api/certificates/{id}/pem : Descarga el certificado PEM.
     */
    @GetMapping("/{id}/pem")
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(
        summary = "Descargar certificado PEM",
        description = "Descarga el contenido PEM del certificado."
    )
    public ResponseEntity<String> downloadPem(@PathVariable Long id) {
        log.info("GET /api/certificates/{}/pem - Descargando PEM", id);

        Certificate certificate = certificateService.getCertificateById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found: " + id));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentDisposition(
            ContentDisposition.attachment()
                .filename("certificate_" + certificate.getSerialNumber() + ".pem")
                .build()
        );

        return ResponseEntity.ok()
            .headers(headers)
            .body(certificate.getPemContent());
    }

    /**
     * GET /api/certificates/entity/{entityId} : Lista certificados por entidad.
     */
    @GetMapping("/entity/{entityId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(
        summary = "Listar certificados por entidad",
        description = "Lista todos los certificados de una entidad bancaria específica."
    )
    public ResponseEntity<List<CertificateDTO>> getCertificatesByEntity(@PathVariable String entityId) {
        log.info("GET /api/certificates/entity/{} - Listando certificados", entityId);

        List<Certificate> certificates = certificateService.getCertificatesByEntity(entityId);
        List<CertificateDTO> dtos = certificates.stream()
            .map(certificateMapper::toDto)
            .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * GET /api/certificates/expiring/{days} : Certificados por expirar.
     */
    @GetMapping("/expiring/{days}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(
        summary = "Certificados por expirar",
        description = "Lista certificados que expiran en los próximos X días."
    )
    public ResponseEntity<List<CertificateDTO>> getExpiringCertificates(@PathVariable int days) {
        log.info("GET /api/certificates/expiring/{} - Obteniendo certificados por expirar", days);

        List<Certificate> certificates = certificateService.getCertificatesExpiringInDays(days);
        List<CertificateDTO> dtos = certificates.stream()
            .map(certificateMapper::toDto)
            .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * POST /api/certificates/{id}/activate : Activa un certificado.
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Activar certificado",
        description = "Activa un certificado desactivado."
    )
    public ResponseEntity<CertificateDTO> activateCertificate(
        @PathVariable Long id,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        log.info("POST /api/certificates/{}/activate - Activando certificado, user: {}", id, userId);

        try {
            Certificate certificate = certificateService.activateCertificate(id, userId, null, ipAddress);
            return ResponseEntity.ok(certificateMapper.toDto(certificate));
        } catch (MissingCertificateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * POST /api/certificates/{id}/deactivate : Desactiva un certificado.
     */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Desactivar certificado",
        description = "Desactiva un certificado (no se usará para desencriptar QRs)."
    )
    public ResponseEntity<CertificateDTO> deactivateCertificate(
        @PathVariable Long id,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        log.info("POST /api/certificates/{}/deactivate - Desactivando certificado, user: {}", id, userId);

        try {
            Certificate certificate = certificateService.deactivateCertificate(id, userId, null, ipAddress);
            return ResponseEntity.ok(certificateMapper.toDto(certificate));
        } catch (MissingCertificateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * POST /api/certificates/{id}/revoke : Revoca un certificado.
     */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Revocar certificado",
        description = "Revoca un certificado de forma permanente. Esta acción es irreversible."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Certificado revocado exitosamente"),
        @ApiResponse(responseCode = "404", description = "Certificado no encontrado", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CertificateDTO> revokeCertificate(
        @PathVariable Long id,
        @Valid @RequestBody RevokeCertificateRequest request,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        log.warn("POST /api/certificates/{}/revoke - Revocando certificado, user: {}, reason: {}",
                 id, userId, request.getReason());

        try {
            Certificate certificate = certificateService.revokeCertificate(
                id, request.getReason(), userId, null, ipAddress
            );
            return ResponseEntity.ok(certificateMapper.toDto(certificate));
        } catch (MissingCertificateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * GET /api/certificates/audits : Consulta auditorías de certificados.
     */
    @GetMapping("/audits")
    @PreAuthorize("hasRole('ADMIN') or hasRole('AUDITOR')")
    @Operation(
        summary = "Consultar auditorías de certificados",
        description = "Consulta logs de auditoría de operaciones sobre certificados con filtros avanzados."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Auditorías consultadas exitosamente"),
        @ApiResponse(responseCode = "400", description = "Parámetros de filtro inválidos")
    })
    public ResponseEntity<Page<bo.com.sintesis.mdqr.base.domain.CertificateAuditLog>> getCertificateAudits(
        @RequestParam(required = false) Long certificateId,
        @RequestParam(required = false) String serialNumber,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) Boolean success,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant fromDate,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant toDate,
        @RequestParam(defaultValue = "0") Integer page,
        @RequestParam(defaultValue = "20") Integer size,
        @RequestParam(defaultValue = "timestamp,desc") String sort
    ) {
        log.info("GET /api/certificates/audits - page={}, size={}, action={}", page, size, action);

        // Validar tamaño de página
        if (size > 100) {
            log.warn("Tamaño de página solicitado ({}) excede el máximo (100), ajustando", size);
            size = 100;
        }

        // Crear Pageable
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
            page,
            size,
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "timestamp")
        );

        // TODO: Implementar servicio de auditoría con filtros
        // Por ahora retornamos página vacía
        Page<bo.com.sintesis.mdqr.base.domain.CertificateAuditLog> audits =
            org.springframework.data.domain.Page.empty(pageable);

        log.info("GET /api/certificates/audits - {} resultados encontrados", audits.getTotalElements());

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", String.valueOf(audits.getTotalElements()));

        return ResponseEntity.ok().headers(headers).body(audits);
    }

    /**
     * POST /api/certificates/{id}/replace : Reemplaza un certificado con una nueva versión.
     */
    @PostMapping("/{id}/replace")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Reemplazar certificado",
        description = "Reemplaza un certificado con una nueva versión. El certificado anterior se marca como SUPERSEDED."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Certificado reemplazado exitosamente", content = @Content(schema = @Schema(implementation = CertificateDTO.class))),
        @ApiResponse(responseCode = "400", description = "Certificado inválido", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Certificado no encontrado", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Certificado duplicado", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CertificateDTO> replaceCertificate(
        @PathVariable Long id,
        @Valid @RequestBody ReplaceCertificateRequest request,
        HttpServletRequest httpRequest,
        Principal principal
    ) {
        String userId = (principal != null && principal.getName() != null) ? principal.getName() : "system";
        String ipAddress = getClientIp(httpRequest);

        log.info("POST /api/certificates/{}/replace - Reemplazando certificado, user: {}", id, userId);

        try {
            Certificate newCertificate = certificateService.replaceCertificate(
                id, request.getNewPemContent(), request.getChangeReason(), userId, null, ipAddress
            );

            CertificateDTO dto = certificateMapper.toDto(newCertificate);

            return ResponseEntity
                .created(URI.create("/api/certificates/" + newCertificate.getId()))
                .body(dto);

        } catch (MissingCertificateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (DuplicateCertificateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("POST /api/certificates/{}/replace - Error: {}", id, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error al reemplazar certificado: " + e.getMessage());
        }
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

    /**
     * Exception for 4xx/5xx responses with ProblemDetail.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class ResponseStatusException extends RuntimeException {
        private final HttpStatus status;

        public ResponseStatusException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatusException(ResponseStatusException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(problemDetail);
    }
}
