package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.domain.Certificate;
import bo.com.sintesis.mdqr.base.domain.CertificateVersion;
import bo.com.sintesis.mdqr.base.repository.CertificateRepository;
import bo.com.sintesis.mdqr.base.service.exception.CertificateInactiveException;
import bo.com.sintesis.mdqr.base.service.exception.CertificateRevokedException;
import bo.com.sintesis.mdqr.base.service.exception.DuplicateCertificateException;
import bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Servicio principal para gestión de certificados públicos.
 * Maneja ciclo de vida completo: upload, consulta, reemplazo, revocación.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final CertificateValidationService validationService;
    private final CertificateVersionService versionService;
    private final CertificateAuditService auditService;

    // ============================================================
    // CRÍTICO: Método usado para desencriptar QRs
    // ============================================================

    /**
     * Obtiene el contenido PEM de un certificado por serial number.
     * CRÍTICO: Este método es usado por el servicio de desencriptación de QRs.
     *
     * @param serialNumber Serial extraído del QR (formato hex lowercase)
     * @return Contenido PEM del certificado
     * @throws MissingCertificateException Si no se encuentra el certificado
     */
    @Transactional(readOnly = true)
    public String getCertificatePemBySerial(String serialNumber) {
        log.debug("Looking up certificate PEM by serial: {}", serialNumber);

        // Normalizar serial a minúsculas para búsqueda case-insensitive
        String normalizedSerial = serialNumber.toLowerCase();
        log.debug("Normalized serial to lowercase: {}", normalizedSerial);

        Optional<Certificate> certOpt = certificateRepository
            .findBySerialNumberAndIsCurrentVersionTrue(normalizedSerial);

        if (certOpt.isEmpty()) {
            log.warn("Certificate not found for serial: {}", serialNumber);
            throw new MissingCertificateException(
                "No active certificate found with serial: " + serialNumber
            );
        }

        Certificate certificate = certOpt.get();

        // Validaciones adicionales
        if (certificate.getIsRevoked()) {
            log.error("Attempted to use revoked certificate - Serial: {}", serialNumber);
            throw new CertificateRevokedException(
                "Certificate is revoked: " + serialNumber
            );
        }

        if (!certificate.getIsActive()) {
            log.error("Attempted to use inactive certificate - Serial: {}", serialNumber);
            throw new CertificateInactiveException(
                "Certificate is inactive: " + serialNumber
            );
        }

        log.info("Certificate found for QR decryption - Serial: {}, Entity: {}",
                 serialNumber, certificate.getEntityId());

        return certificate.getPemContent();
    }

    /**
     * Obtiene un certificado X509 parseado por serial (para desencriptación).
     */
    @Transactional(readOnly = true)
    public X509Certificate getX509CertificateBySerial(String serialNumber)
            throws CertificateException {
        String pemContent = getCertificatePemBySerial(serialNumber);
        return validationService.parsePemCertificate(pemContent);
    }

    // ============================================================
    // Upload y validación
    // ============================================================

    /**
     * Sube un nuevo certificado al sistema.
     *
     * @param pemContent Contenido PEM del certificado
     * @param entityId ID de la entidad bancaria
     * @param entityName Nombre de la entidad
     * @param description Descripción opcional
     * @param tags Tags opcionales
     * @param userId Usuario que sube el certificado
     * @param userEmail Email del usuario
     * @param ipAddress IP del usuario
     * @return Certificado creado
     */
    @Transactional
    public Certificate uploadCertificate(String pemContent, String entityId, String entityName,
                                         String description, String[] tags, String[] notificationEmails,
                                         String userId, String userEmail, String ipAddress)
            throws CertificateException, DuplicateCertificateException {
        log.info("Uploading new certificate for entity: {}", entityId);

        // 1. Validar y extraer metadata
        CertificateValidationService.CertificateMetadata metadata =
            validationService.validateAndExtractMetadata(pemContent);

        // 2. Verificar duplicados por fingerprint
        if (certificateRepository.existsByFingerprintSha256(metadata.getFingerprintSha256())) {
            log.warn("Duplicate certificate detected - Fingerprint: {}", metadata.getFingerprintSha256());
            auditService.logUpload(createCertificateFromMetadata(metadata, entityId, pemContent),
                                   userId, userEmail, ipAddress, false,
                                   "Duplicate certificate - fingerprint already exists");
            throw new DuplicateCertificateException(
                "Certificate already exists with fingerprint: " + metadata.getFingerprintSha256()
            );
        }

        // 3. Crear entidad Certificate
        Certificate certificate = new Certificate();
        certificate.setSerialNumber(metadata.getSerialNumber());
        certificate.setFingerprintSha256(metadata.getFingerprintSha256());
        certificate.setEntityId(entityId);
        certificate.setEntityName(entityName);
        certificate.setPemContent(pemContent);
        certificate.setSubjectDn(metadata.getSubjectDn());
        certificate.setIssuerDn(metadata.getIssuerDn());
        certificate.setIssuerCn(metadata.getIssuerCn());
        certificate.setValidFrom(metadata.getValidFrom());
        certificate.setValidTo(metadata.getValidTo());
        certificate.setDescription(description);
        certificate.setTags(tags);
        certificate.setNotificationEmails(notificationEmails);

        // 4. Determinar estado inicial basado en fechas
        certificate.setStatus(determineInitialStatus(metadata));

        // 5. Valores por defecto
        certificate.setVersionNumber(1);
        certificate.setIsCurrentVersion(true);
        certificate.setIsActive(true);
        certificate.setIsRevoked(false);

        // 6. Guardar
        Certificate saved = certificateRepository.save(certificate);

        // 7. Auditar
        auditService.logUpload(saved, userId, userEmail, ipAddress, true, null);

        log.info("Certificate uploaded successfully - ID: {}, Serial: {}, Entity: {}",
                 saved.getId(), saved.getSerialNumber(), saved.getEntityId());

        return saved;
    }

    /**
     * Valida un certificado sin guardarlo.
     */
    @Transactional(readOnly = true)
    public CertificateValidationService.CertificateMetadata validateCertificate(
            String pemContent, String userId, String userEmail, String ipAddress)
            throws CertificateException {
        log.debug("Validating certificate (no upload)");

        try {
            CertificateValidationService.CertificateMetadata metadata =
                validationService.validateAndExtractMetadata(pemContent);

            auditService.logValidation(metadata.getSerialNumber(), userId, userEmail,
                                       ipAddress, true, null);

            return metadata;
        } catch (CertificateException e) {
            auditService.logValidation(null, userId, userEmail, ipAddress, false, e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // Consultas y listados
    // ============================================================

    /**
     * Obtiene un certificado por ID.
     */
    @Transactional(readOnly = true)
    public Optional<Certificate> getCertificateById(Long id) {
        return certificateRepository.findById(id);
    }

    /**
     * Obtiene un certificado por serial (sin filtros).
     */
    @Transactional(readOnly = true)
    public Optional<Certificate> getCertificateBySerial(String serialNumber) {
        return certificateRepository.findBySerialNumber(serialNumber);
    }

    /**
     * Lista todos los certificados activos (current version, no revocados).
     */
    @Transactional(readOnly = true)
    public List<Certificate> getAllActiveCertificates() {
        return certificateRepository.findAllActive();
    }

    /**
     * Lista certificados con paginación.
     */
    @Transactional(readOnly = true)
    public Page<Certificate> getCertificates(Pageable pageable) {
        return certificateRepository.findByIsCurrentVersionTrueAndIsRevokedFalse(pageable);
    }

    /**
     * Lista certificados por entidad.
     */
    @Transactional(readOnly = true)
    public List<Certificate> getCertificatesByEntity(String entityId) {
        return certificateRepository.findByEntityId(entityId);
    }

    /**
     * Certificados que expiran en los próximos X días.
     */
    @Transactional(readOnly = true)
    public List<Certificate> getCertificatesExpiringInDays(int days) {
        Instant now = Instant.now();
        Instant expirationDate = now.plusSeconds((long) days * 24 * 60 * 60);
        return certificateRepository.findExpiringBefore(now, expirationDate);
    }

    /**
     * Certificados expirados.
     */
    @Transactional(readOnly = true)
    public List<Certificate> getExpiredCertificates() {
        return certificateRepository.findExpired(Instant.now());
    }

    // ============================================================
    // Operaciones de ciclo de vida
    // ============================================================

    /**
     * Activa un certificado.
     */
    @Transactional
    public Certificate activateCertificate(Long id, String userId, String userEmail, String ipAddress) {
        Certificate certificate = certificateRepository.findById(id)
            .orElseThrow(() -> new MissingCertificateException("Certificate not found: " + id));

        if (certificate.getIsRevoked()) {
            throw new IllegalStateException("Cannot activate revoked certificate");
        }

        certificate.setIsActive(true);
        Certificate saved = certificateRepository.save(certificate);

        auditService.logActivate(saved, userId, userEmail, ipAddress);

        log.info("Certificate activated - ID: {}", id);
        return saved;
    }

    /**
     * Desactiva un certificado.
     */
    @Transactional
    public Certificate deactivateCertificate(Long id, String userId, String userEmail, String ipAddress) {
        Certificate certificate = certificateRepository.findById(id)
            .orElseThrow(() -> new MissingCertificateException("Certificate not found: " + id));

        certificate.setIsActive(false);
        Certificate saved = certificateRepository.save(certificate);

        auditService.logDeactivate(saved, userId, userEmail, ipAddress);

        log.info("Certificate deactivated - ID: {}", id);
        return saved;
    }

    /**
     * Revoca un certificado.
     */
    @Transactional
    public Certificate revokeCertificate(Long id, String reason, String userId,
                                         String userEmail, String ipAddress) {
        Certificate certificate = certificateRepository.findById(id)
            .orElseThrow(() -> new MissingCertificateException("Certificate not found: " + id));

        // Crear snapshot antes de revocar
        versionService.createVersionSnapshot(certificate, userId, reason,
                                              CertificateVersion.ChangeType.REVOCATION);

        // Revocar
        certificate.setIsRevoked(true);
        certificate.setRevokedAt(Instant.now());
        certificate.setRevokedBy(userId);
        certificate.setRevokedReason(reason);
        certificate.setStatus(Certificate.CertificateStatus.REVOKED);
        certificate.setIsActive(false);

        Certificate saved = certificateRepository.save(certificate);

        auditService.logRevoke(saved, reason, userId, userEmail, ipAddress);

        log.warn("Certificate revoked - ID: {}, Reason: {}", id, reason);
        return saved;
    }

    /**
     * Reemplaza un certificado con una nueva versión.
     */
    @Transactional
    public Certificate replaceCertificate(Long oldCertificateId, String newPemContent,
                                          String changeReason, String userId,
                                          String userEmail, String ipAddress)
            throws CertificateException, DuplicateCertificateException {
        log.info("Replacing certificate ID: {}", oldCertificateId);

        // 1. Obtener certificado anterior
        Certificate oldCertificate = certificateRepository.findById(oldCertificateId)
            .orElseThrow(() -> new MissingCertificateException("Certificate not found: " + oldCertificateId));

        // 2. Validar nuevo certificado
        CertificateValidationService.CertificateMetadata newMetadata =
            validationService.validateAndExtractMetadata(newPemContent);

        // 3. Verificar que no sea duplicado
        if (certificateRepository.existsByFingerprintSha256(newMetadata.getFingerprintSha256())) {
            throw new DuplicateCertificateException("New certificate already exists");
        }

        // 4. Crear snapshot del certificado anterior
        versionService.createVersionSnapshot(oldCertificate, userId, changeReason,
                                              CertificateVersion.ChangeType.REPLACEMENT);

        // 5. Marcar certificado anterior como SUPERSEDED
        oldCertificate.setIsCurrentVersion(false);
        oldCertificate.setStatus(Certificate.CertificateStatus.SUPERSEDED);
        oldCertificate.setIsActive(false);
        certificateRepository.save(oldCertificate);

        // 6. Crear nuevo certificado (misma entidad, nueva versión)
        Certificate newCertificate = new Certificate();
        newCertificate.setSerialNumber(newMetadata.getSerialNumber());
        newCertificate.setFingerprintSha256(newMetadata.getFingerprintSha256());
        newCertificate.setEntityId(oldCertificate.getEntityId());
        newCertificate.setEntityName(oldCertificate.getEntityName());
        newCertificate.setPemContent(newPemContent);
        newCertificate.setSubjectDn(newMetadata.getSubjectDn());
        newCertificate.setIssuerDn(newMetadata.getIssuerDn());
        newCertificate.setIssuerCn(newMetadata.getIssuerCn());
        newCertificate.setValidFrom(newMetadata.getValidFrom());
        newCertificate.setValidTo(newMetadata.getValidTo());
        newCertificate.setDescription(oldCertificate.getDescription());
        newCertificate.setTags(oldCertificate.getTags());
        newCertificate.setNotificationEmails(oldCertificate.getNotificationEmails());
        newCertificate.setStatus(determineInitialStatus(newMetadata));
        newCertificate.setVersionNumber(oldCertificate.getVersionNumber() + 1);
        newCertificate.setIsCurrentVersion(true);
        newCertificate.setIsActive(true);
        newCertificate.setIsRevoked(false);

        Certificate savedNew = certificateRepository.save(newCertificate);

        // 7. Auditar reemplazo
        auditService.logReplace(oldCertificate, savedNew, userId, userEmail, ipAddress);

        log.info("Certificate replaced - Old ID: {}, New ID: {}, New Serial: {}",
                 oldCertificateId, savedNew.getId(), savedNew.getSerialNumber());

        return savedNew;
    }

    // ============================================================
    // Actualización de estados (ejecutar en job scheduled)
    // ============================================================

    /**
     * Actualiza estados de certificados basado en fechas de expiración.
     * DEBE ejecutarse en un job scheduled diario.
     */
    @Transactional
    public void updateCertificateStatuses() {
        log.info("Starting scheduled certificate status update");

        List<Certificate> activeCerts = certificateRepository.findAllActive();
        int updated = 0;

        for (Certificate cert : activeCerts) {
            Certificate.CertificateStatus newStatus = determineCurrentStatus(cert);

            if (newStatus != cert.getStatus()) {
                cert.setStatus(newStatus);
                certificateRepository.save(cert);
                updated++;
                log.debug("Updated certificate status - ID: {}, Status: {}", cert.getId(), newStatus);
            }
        }

        log.info("Certificate status update completed - {} certificates updated", updated);
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private Certificate.CertificateStatus determineInitialStatus(
            CertificateValidationService.CertificateMetadata metadata) {
        Instant now = Instant.now();

        if (metadata.getValidTo().isBefore(now)) {
            return Certificate.CertificateStatus.EXPIRED;
        }

        long daysRemaining = java.time.Duration.between(now, metadata.getValidTo()).toDays();
        if (daysRemaining <= 30) {
            return Certificate.CertificateStatus.EXPIRING_SOON;
        }

        return Certificate.CertificateStatus.ACTIVE;
    }

    private Certificate.CertificateStatus determineCurrentStatus(Certificate certificate) {
        if (certificate.getIsRevoked()) {
            return Certificate.CertificateStatus.REVOKED;
        }

        if (!certificate.getIsCurrentVersion()) {
            return Certificate.CertificateStatus.SUPERSEDED;
        }

        if (certificate.isExpired()) {
            return Certificate.CertificateStatus.EXPIRED;
        }

        if (certificate.isExpiringSoon()) {
            return Certificate.CertificateStatus.EXPIRING_SOON;
        }

        return Certificate.CertificateStatus.ACTIVE;
    }

    private Certificate createCertificateFromMetadata(
            CertificateValidationService.CertificateMetadata metadata,
            String entityId, String pemContent) {
        Certificate cert = new Certificate();
        cert.setSerialNumber(metadata.getSerialNumber());
        cert.setFingerprintSha256(metadata.getFingerprintSha256());
        cert.setEntityId(entityId);
        cert.setPemContent(pemContent);
        return cert;
    }

}
