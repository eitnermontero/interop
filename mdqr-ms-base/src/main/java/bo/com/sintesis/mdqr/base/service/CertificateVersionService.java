package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.domain.Certificate;
import bo.com.sintesis.mdqr.base.domain.CertificateVersion;
import bo.com.sintesis.mdqr.base.repository.CertificateVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Servicio para gestión de historial de versiones de certificados.
 * Preserva snapshots de certificados reemplazados para rollback y auditoría.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateVersionService {

    private final CertificateVersionRepository versionRepository;

    /**
     * Crea snapshot de una versión de certificado antes de reemplazarlo.
     *
     * @param certificate Certificado a preservar
     * @param replacedBy Usuario que reemplaza el certificado
     * @param changeReason Razón del cambio
     * @param changeType Tipo de cambio
     * @return Versión creada
     */
    @Transactional
    public CertificateVersion createVersionSnapshot(Certificate certificate, String replacedBy,
                                                     String changeReason,
                                                     CertificateVersion.ChangeType changeType) {
        log.info("Creating version snapshot for certificate ID: {}, version: {}",
                 certificate.getId(), certificate.getVersionNumber());

        CertificateVersion version = new CertificateVersion();
        version.setCertificateId(certificate.getId());
        version.setVersionNumber(certificate.getVersionNumber());

        // Snapshot del contenido PEM y metadata crítica
        version.setPemContentSnapshot(certificate.getPemContent());
        version.setFingerprintSha256Snapshot(certificate.getFingerprintSha256());
        version.setValidFromSnapshot(certificate.getValidFrom());
        version.setValidToSnapshot(certificate.getValidTo());
        version.setSubjectDnSnapshot(certificate.getSubjectDn());
        version.setIssuerDnSnapshot(certificate.getIssuerDn());

        // Metadata del cambio
        version.setReplacedAt(Instant.now());
        version.setReplacedBy(replacedBy != null ? replacedBy : "system");
        version.setChangeReason(changeReason);
        version.setChangeType(changeType != null ? changeType.name() : null);

        CertificateVersion saved = versionRepository.save(version);

        log.info("Version snapshot created - Certificate ID: {}, Version: {}, ChangeType: {}",
                 certificate.getId(), saved.getVersionNumber(), changeType);

        return saved;
    }

    /**
     * Obtiene todas las versiones de un certificado.
     *
     * @param certificateId ID del certificado
     * @return Lista de versiones ordenadas por número de versión descendente
     */
    @Transactional(readOnly = true)
    public List<CertificateVersion> getVersionHistory(Long certificateId) {
        log.debug("Retrieving version history for certificate ID: {}", certificateId);
        return versionRepository.findByCertificateIdOrderByVersionNumberDesc(certificateId);
    }

    /**
     * Obtiene una versión específica de un certificado.
     *
     * @param certificateId ID del certificado
     * @param versionNumber Número de versión
     * @return Versión encontrada
     */
    @Transactional(readOnly = true)
    public Optional<CertificateVersion> getSpecificVersion(Long certificateId, Integer versionNumber) {
        log.debug("Retrieving version {} for certificate ID: {}", versionNumber, certificateId);
        return versionRepository.findByCertificateIdAndVersionNumber(certificateId, versionNumber);
    }

    /**
     * Obtiene la última versión archivada de un certificado.
     *
     * @param certificateId ID del certificado
     * @return Última versión archivada
     */
    @Transactional(readOnly = true)
    public Optional<CertificateVersion> getLatestVersion(Long certificateId) {
        log.debug("Retrieving latest archived version for certificate ID: {}", certificateId);
        return versionRepository.findLatestVersion(certificateId);
    }

    /**
     * Cuenta cuántas versiones tiene un certificado.
     *
     * @param certificateId ID del certificado
     * @return Cantidad de versiones
     */
    @Transactional(readOnly = true)
    public long countVersions(Long certificateId) {
        long count = versionRepository.countByCertificateId(certificateId);
        log.debug("Certificate ID {} has {} archived versions", certificateId, count);
        return count;
    }

    /**
     * Verifica si un certificado tiene versiones anteriores.
     *
     * @param certificateId ID del certificado
     * @return true si tiene versiones anteriores
     */
    @Transactional(readOnly = true)
    public boolean hasVersionHistory(Long certificateId) {
        return countVersions(certificateId) > 0;
    }
}
