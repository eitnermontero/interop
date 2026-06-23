package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.domain.AdminAuditLog;
import bo.com.sintesis.mdqr.base.domain.DecryptionLog;
import bo.com.sintesis.mdqr.base.repository.AdminAuditLogRepository;
import bo.com.sintesis.mdqr.base.repository.DecryptionLogRepository;
import bo.com.sintesis.mdqr.base.security.SecurityUtils;
import bo.com.sintesis.mdqr.base.service.dto.AuditLogFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de auditoría para registro de operaciones.
 * <p>
 * Responsabilidades:
 * - Registro asíncrono de desencriptaciones de QR
 * - Registro de acciones administrativas (importar/revocar certificados)
 * - Consulta de logs con filtros avanzados
 * - Paginación y ordenamiento de resultados
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final DecryptionLogRepository decryptionLogRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final CryptoService cryptoService;

    /**
     * Registra una operación de desencriptación de QR de forma asíncrona.
     * No bloquea el hilo principal.
     *
     * @param logId ID único del log
     * @param certificateCode Código del certificado utilizado
     * @param qrString String del QR completo
     * @param success Si la desencriptación fue exitosa
     * @param errorMessage Mensaje de error si falló
     * @param processingTimeMs Tiempo de procesamiento en milisegundos
     * @param entityId ID de la entidad solicitante
     * @param qrType Tipo de QR detectado
     */
    @Async("auditExecutor")
    @Transactional
    public void logDecryption(
        String logId,
        String certificateCode,
        String qrString,
        boolean success,
        String errorMessage,
        Long processingTimeMs,
        String entityId,
        String qrType
    ) {
        try {
            log.debug("Registrando auditoría de desencriptación: logId={}, success={}", logId, success);

            // Obtener información del usuario autenticado
            String keycloakClientId = SecurityUtils.getCurrentUserClientId().orElse("UNKNOWN");
            String username = SecurityUtils.getCurrentUserLogin().orElse("SYSTEM");

            // Hash del QR para auditoría (no guardar el QR completo por seguridad)
            String qrStringHash = cryptoService.sha256(qrString);

            // Crear registro de auditoría
            DecryptionLog auditLog = new DecryptionLog();
            auditLog.setLogId(logId);
            auditLog.setKeycloakClientId(keycloakClientId);
            auditLog.setMtlsCertCn(keycloakClientId); // Usar clientId como CN por ahora
            auditLog.setEntityIdRequest(entityId);
            auditLog.setQrStringHash(qrStringHash);
            auditLog.setQrType(qrType);
            auditLog.setStatus(success ? "SUCCESS" : "ERROR");
            auditLog.setErrorMessage(errorMessage);
            auditLog.setProcessingTimeMs(processingTimeMs);
            auditLog.setCreatedBy(username);
            auditLog.setCreatedDate(Instant.now());

            // Certificate relationship - dejar null por ahora
            // TODO: Buscar Certificate por código y establecer relación si es necesario
            auditLog.setCertificate(null);

            // Guardar en base de datos
            decryptionLogRepository.save(auditLog);

            AuditService.log.info("Auditoría de desencriptación registrada: logId={}, clientId={}, status={}",
                logId, keycloakClientId, auditLog.getStatus());

        } catch (Exception e) {
            // No lanzar excepción para no afectar el flujo principal
            AuditService.log.error("Error al registrar auditoría de desencriptación: {}", e.getMessage(), e);
        }
    }

    /**
     * Registra una acción administrativa de forma asíncrona.
     *
     * @param action Acción realizada (IMPORT, REVOKE, INVALIDATE_CACHE, etc.)
     * @param entityType Tipo de entidad afectada (CERTIFICATE, etc.)
     * @param entityId ID de la entidad afectada
     * @param details Detalles adicionales de la acción
     * @param success Si la acción fue exitosa
     * @param errorMessage Mensaje de error si falló
     */
    @Async("auditExecutor")
    @Transactional
    public void logAdminAction(
        String action,
        String entityType,
        String entityId,
        String details,
        boolean success,
        String errorMessage
    ) {
        try {
            log.debug("Registrando auditoría administrativa: action={}, entityType={}, success={}",
                action, entityType, success);

            // Obtener información del usuario autenticado
            String username = SecurityUtils.getCurrentUserLogin().orElse("SYSTEM");
            String keycloakClientId = SecurityUtils.getCurrentUserClientId().orElse("UNKNOWN");

            // Crear registro de auditoría
            AdminAuditLog auditLog = new AdminAuditLog();
            auditLog.setAction(action);
            auditLog.setResourceType(entityType);
            auditLog.setResourceId(entityId);

            // Combinar detalles con status y error en JSON
            String detailsJson = String.format(
                "{\"details\":\"%s\",\"success\":%b,\"errorMessage\":\"%s\"}",
                details != null ? details.replace("\"", "\\\"") : "",
                success,
                errorMessage != null ? errorMessage.replace("\"", "\\\"") : ""
            );
            auditLog.setOldValue(detailsJson);

            auditLog.setKeycloakUserId(keycloakClientId);
            auditLog.setKeycloakUsername(username);
            auditLog.setCreatedAt(Instant.now());

            // Guardar en base de datos
            adminAuditLogRepository.save(auditLog);

            AuditService.log.info("Auditoría administrativa registrada: action={}, resourceType={}, resourceId={}, success={}",
                action, entityType, entityId, success);

        } catch (Exception e) {
            // No lanzar excepción para no afectar el flujo principal
            AuditService.log.error("Error al registrar auditoría administrativa: {}", e.getMessage(), e);
        }
    }

    /**
     * Consulta logs de desencriptación con filtros avanzados.
     *
     * @param filter Filtros de búsqueda
     * @return Página de logs
     */
    @Transactional(readOnly = true)
    public Page<DecryptionLog> queryDecryptionLogs(AuditLogFilter filter) {
        log.debug("Consultando logs de desencriptación con filtros: {}", filter);

        // Construir Specification con filtros dinámicos
        Specification<DecryptionLog> spec = buildDecryptionLogSpecification(filter);

        // Construir Pageable con ordenamiento
        Sort sort = Sort.by(
            "desc".equalsIgnoreCase(filter.getOrder()) ? Sort.Direction.DESC : Sort.Direction.ASC,
            filter.getSort()
        );
        Pageable pageable = PageRequest.of(filter.getPage(), filter.getSize(), sort);

        // Ejecutar consulta
        Page<DecryptionLog> result = decryptionLogRepository.findAll(spec, pageable);

        log.info("Logs consultados: {} resultados, página {}/{}",
            result.getTotalElements(), result.getNumber() + 1, result.getTotalPages());

        return result;
    }

    /**
     * Construye una Specification JPA para filtros dinámicos de DecryptionLog.
     */
    private Specification<DecryptionLog> buildDecryptionLogSpecification(AuditLogFilter filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Filtro por keycloakClientId
            if (filter.getKeycloakClientId() != null && !filter.getKeycloakClientId().isEmpty()) {
                predicates.add(criteriaBuilder.equal(
                    root.get("keycloakClientId"),
                    filter.getKeycloakClientId()
                ));
            }

            // Filtro por certificateCode - buscar en la relación Certificate
            if (filter.getCertificateCode() != null && !filter.getCertificateCode().isEmpty()) {
                // TODO: Ajustar cuando se implemente la relación con Certificate
                // Por ahora omitimos este filtro
            }

            // Filtro por entityId - usar entityIdRequest
            if (filter.getEntityId() != null && !filter.getEntityId().isEmpty()) {
                predicates.add(criteriaBuilder.equal(
                    root.get("entityIdRequest"),
                    filter.getEntityId()
                ));
            }

            // Filtro por status
            if (filter.getStatus() != null && !filter.getStatus().isEmpty()) {
                predicates.add(criteriaBuilder.equal(
                    root.get("status"),
                    filter.getStatus()
                ));
            }

            // Filtro por rango de fechas
            if (filter.getFromDate() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                    root.get("createdDate"),
                    filter.getFromDate()
                ));
            }

            if (filter.getToDate() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                    root.get("createdDate"),
                    filter.getToDate()
                ));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Consulta logs administrativos con filtros.
     *
     * @param action Filtro por acción (opcional)
     * @param entityType Filtro por tipo de entidad (opcional)
     * @param fromDate Filtro por fecha desde (opcional)
     * @param toDate Filtro por fecha hasta (opcional)
     * @param pageable Configuración de paginación
     * @return Página de logs administrativos
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLog> queryAdminLogs(
        String action,
        String entityType,
        Instant fromDate,
        Instant toDate,
        Pageable pageable
    ) {
        log.debug("Consultando logs administrativos: action={}, entityType={}", action, entityType);

        Specification<AdminAuditLog> spec = buildAdminLogSpecification(action, entityType, fromDate, toDate);

        Page<AdminAuditLog> result = adminAuditLogRepository.findAll(spec, pageable);

        log.info("Logs administrativos consultados: {} resultados", result.getTotalElements());

        return result;
    }

    /**
     * Construye una Specification JPA para filtros dinámicos de AdminAuditLog.
     */
    private Specification<AdminAuditLog> buildAdminLogSpecification(
        String action,
        String entityType,
        Instant fromDate,
        Instant toDate
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (action != null && !action.isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("action"), action));
            }

            if (entityType != null && !entityType.isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("resourceType"), entityType));
            }

            if (fromDate != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
            }

            if (toDate != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), toDate));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
