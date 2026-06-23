package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.domain.Certificate;
import bo.com.sintesis.mdqr.base.domain.CertificateAuditLog;
import bo.com.sintesis.mdqr.base.repository.CertificateAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio para auditoría de operaciones sobre certificados.
 * CRÍTICO: Registra TODAS las operaciones incluyendo desencriptación de QRs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateAuditService {

    private final CertificateAuditLogRepository auditLogRepository;

    /**
     * Registra operación de subida de certificado.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logUpload(Certificate certificate, String userId, String userEmail,
                          String ipAddress, boolean success, String errorMessage) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.UPLOAD,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificate.getId());
        auditLog.setSerialNumber(certificate.getSerialNumber());
        auditLog.setEntityIdRequest(certificate.getEntityId());
        auditLog.setSuccess(success);
        auditLog.setErrorMessage(errorMessage);

        if (success) {
            auditLog.setAfterState(buildCertificateState(certificate));
        }

        auditLogRepository.save(auditLog);
        log.debug("Logged UPLOAD action for certificate serial: {}", certificate.getSerialNumber());
    }

    /**
     * Registra operación de validación (sin guardar).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logValidation(String serialNumber, String userId, String userEmail,
                              String ipAddress, boolean success, String errorMessage) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.VALIDATE,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setSerialNumber(serialNumber);
        auditLog.setSuccess(success);
        auditLog.setErrorMessage(errorMessage);

        auditLogRepository.save(auditLog);
        log.debug("Logged VALIDATE action for serial: {}", serialNumber);
    }

    /**
     * Registra activación de certificado.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logActivate(Certificate certificate, String userId, String userEmail, String ipAddress) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.ACTIVATE,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificate.getId());
        auditLog.setSerialNumber(certificate.getSerialNumber());
        auditLog.setEntityIdRequest(certificate.getEntityId());
        auditLog.setSuccess(true);
        auditLog.setAfterState(buildCertificateState(certificate));

        auditLogRepository.save(auditLog);
        log.debug("Logged ACTIVATE action for certificate ID: {}", certificate.getId());
    }

    /**
     * Registra desactivación de certificado.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDeactivate(Certificate certificate, String userId, String userEmail, String ipAddress) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.DEACTIVATE,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificate.getId());
        auditLog.setSerialNumber(certificate.getSerialNumber());
        auditLog.setEntityIdRequest(certificate.getEntityId());
        auditLog.setSuccess(true);
        auditLog.setBeforeState(buildCertificateState(certificate));

        auditLogRepository.save(auditLog);
        log.debug("Logged DEACTIVATE action for certificate ID: {}", certificate.getId());
    }

    /**
     * Registra revocación de certificado.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRevoke(Certificate certificate, String reason, String userId,
                          String userEmail, String ipAddress) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.REVOKE,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificate.getId());
        auditLog.setSerialNumber(certificate.getSerialNumber());
        auditLog.setEntityIdRequest(certificate.getEntityId());
        auditLog.setSuccess(true);

        Map<String, Object> afterState = buildCertificateState(certificate);
        afterState.put("revokedReason", reason);
        auditLog.setAfterState(afterState);

        auditLogRepository.save(auditLog);
        log.debug("Logged REVOKE action for certificate ID: {}", certificate.getId());
    }

    /**
     * Registra reemplazo de certificado.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logReplace(Certificate oldCertificate, Certificate newCertificate,
                           String userId, String userEmail, String ipAddress) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.REPLACE,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(oldCertificate.getId());
        auditLog.setSerialNumber(oldCertificate.getSerialNumber());
        auditLog.setEntityIdRequest(oldCertificate.getEntityId());
        auditLog.setSuccess(true);
        auditLog.setBeforeState(buildCertificateState(oldCertificate));
        auditLog.setAfterState(buildCertificateState(newCertificate));

        auditLogRepository.save(auditLog);
        log.debug("Logged REPLACE action - Old: {}, New: {}",
                  oldCertificate.getId(), newCertificate.getId());
    }

    /**
     * CRÍTICO: Registra desencriptación de QR (para auditoría de pagos).
     *
     * @param certificateId ID del certificado usado
     * @param serialNumber Serial del certificado
     * @param qrContent Contenido del QR encriptado
     * @param entityId ID de la entidad bancaria
     * @param userId Usuario que realizó la operación
     * @param userEmail Email del usuario
     * @param ipAddress IP desde donde se realizó
     * @param requestId ID de la petición
     * @param processingTimeMs Tiempo de procesamiento
     * @param success Si la desencriptación fue exitosa
     * @param errorMessage Mensaje de error (si falló)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDecryptQr(Long certificateId, String serialNumber, String qrContent,
                             String entityId, String userId, String userEmail, String ipAddress,
                             String requestId, Integer processingTimeMs,
                             boolean success, String errorMessage, String errorCode) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.DECRYPT_QR,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificateId);
        auditLog.setSerialNumber(serialNumber);
        auditLog.setEntityIdRequest(entityId);
        auditLog.setRequestId(requestId);
        auditLog.setProcessingTimeMs(processingTimeMs);
        auditLog.setSuccess(success);
        auditLog.setErrorMessage(errorMessage);
        auditLog.setErrorCode(errorCode);

        // Hash del contenido QR (no guardamos el QR completo por seguridad)
        if (qrContent != null && !qrContent.isEmpty()) {
            auditLog.setQrContentHash(calculateSha256Hash(qrContent));
        }

        auditLogRepository.save(auditLog);

        if (success) {
            log.info("Logged successful DECRYPT_QR - Certificate: {}, Serial: {}, RequestId: {}",
                     certificateId, serialNumber, requestId);
        } else {
            log.warn("Logged failed DECRYPT_QR - Serial: {}, Error: {}, RequestId: {}",
                     serialNumber, errorMessage, requestId);
        }
    }

    /**
     * Registra visualización de certificado.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logView(Long certificateId, String serialNumber, String userId,
                        String userEmail, String ipAddress) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.VIEW,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificateId);
        auditLog.setSerialNumber(serialNumber);
        auditLog.setSuccess(true);

        auditLogRepository.save(auditLog);
        log.trace("Logged VIEW action for certificate ID: {}", certificateId);
    }

    /**
     * Registra descarga de certificado PEM.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDownload(Long certificateId, String serialNumber, String userId,
                            String userEmail, String ipAddress) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.DOWNLOAD,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setCertificateId(certificateId);
        auditLog.setSerialNumber(serialNumber);
        auditLog.setSuccess(true);

        auditLogRepository.save(auditLog);
        log.debug("Logged DOWNLOAD action for certificate ID: {}", certificateId);
    }

    /**
     * Registra búsqueda de certificados.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSearch(String userId, String userEmail, String ipAddress,
                          Map<String, Object> searchCriteria) {
        CertificateAuditLog auditLog = createBaseAuditLog(
            CertificateAuditLog.AuditAction.SEARCH,
            userId,
            userEmail,
            ipAddress
        );

        auditLog.setSuccess(true);
        auditLog.setBeforeState(searchCriteria);

        auditLogRepository.save(auditLog);
        log.trace("Logged SEARCH action by user: {}", userId);
    }

    // ============================================================
    // Helper methods
    // ============================================================

    /**
     * Crea objeto base de auditoría.
     */
    private CertificateAuditLog createBaseAuditLog(CertificateAuditLog.AuditAction action,
                                                    String userId, String userEmail,
                                                    String ipAddress) {
        CertificateAuditLog auditLog = new CertificateAuditLog();
        auditLog.setAction(action);
        auditLog.setUserId(userId != null ? userId : "system");
        auditLog.setUserEmail(userEmail);
        auditLog.setIpAddress(ipAddress);
        auditLog.setTimestamp(Instant.now());
        return auditLog;
    }

    /**
     * Construye snapshot del estado de un certificado.
     */
    private Map<String, Object> buildCertificateState(Certificate certificate) {
        Map<String, Object> state = new HashMap<>();
        state.put("id", certificate.getId());
        state.put("serialNumber", certificate.getSerialNumber());
        state.put("fingerprintSha256", certificate.getFingerprintSha256());
        state.put("entityId", certificate.getEntityId());
        state.put("entityName", certificate.getEntityName());
        state.put("status", certificate.getStatus().name());
        state.put("versionNumber", certificate.getVersionNumber());
        state.put("isCurrentVersion", certificate.getIsCurrentVersion());
        state.put("isActive", certificate.getIsActive());
        state.put("isRevoked", certificate.getIsRevoked());
        state.put("validFrom", certificate.getValidFrom().toString());
        state.put("validTo", certificate.getValidTo().toString());
        return state;
    }

    /**
     * Calcula SHA-256 hash de un string.
     */
    private String calculateSha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Failed to calculate SHA-256 hash", e);
            return null;
        }
    }
}
