package bo.com.sintesis.mdqr.base.service.mapper;

import bo.com.sintesis.mdqr.base.domain.Certificate;
import bo.com.sintesis.mdqr.base.service.dto.CertificateDTO;
import bo.com.sintesis.mdqr.base.service.dto.CertificateDetailDTO;
import org.springframework.stereotype.Component;

/**
 * Mapper para convertir entre entidades Certificate y DTOs.
 */
@Component
public class CertificateMapper {

    /**
     * Convierte Certificate entity a CertificateDTO (sin PEM content).
     */
    public CertificateDTO toDto(Certificate certificate) {
        if (certificate == null) {
            return null;
        }

        return CertificateDTO.builder()
            .id(certificate.getId())
            .serialNumber(certificate.getSerialNumber())
            .fingerprintSha256(certificate.getFingerprintSha256())
            .entityId(certificate.getEntityId())
            .entityName(certificate.getEntityName())
            .subjectDn(certificate.getSubjectDn())
            .issuerDn(certificate.getIssuerDn())
            .issuerCn(certificate.getIssuerCn())
            .validFrom(certificate.getValidFrom())
            .validTo(certificate.getValidTo())
            .daysRemaining(certificate.getDaysRemaining())
            .status(certificate.getStatus() != null ? certificate.getStatus().name() : null)
            .versionNumber(certificate.getVersionNumber())
            .isCurrentVersion(certificate.getIsCurrentVersion())
            .isActive(certificate.getIsActive())
            .isRevoked(certificate.getIsRevoked())
            .revokedAt(certificate.getRevokedAt())
            .revokedBy(certificate.getRevokedBy())
            .revokedReason(certificate.getRevokedReason())
            .description(certificate.getDescription())
            .tags(certificate.getTags())
            .notificationEmails(certificate.getNotificationEmails())
            .createdDate(certificate.getCreatedDate())
            .createdBy(certificate.getCreatedBy())
            .lastModifiedDate(certificate.getLastModifiedDate())
            .lastModifiedBy(certificate.getLastModifiedBy())
            .build();
    }

    /**
     * Convierte Certificate entity a CertificateDetailDTO (incluye PEM content).
     */
    public CertificateDetailDTO toDetailDto(Certificate certificate) {
        if (certificate == null) {
            return null;
        }

        return CertificateDetailDTO.builder()
            .id(certificate.getId())
            .serialNumber(certificate.getSerialNumber())
            .fingerprintSha256(certificate.getFingerprintSha256())
            .entityId(certificate.getEntityId())
            .entityName(certificate.getEntityName())
            .pemContent(certificate.getPemContent())
            .subjectDn(certificate.getSubjectDn())
            .issuerDn(certificate.getIssuerDn())
            .issuerCn(certificate.getIssuerCn())
            .validFrom(certificate.getValidFrom())
            .validTo(certificate.getValidTo())
            .daysRemaining(certificate.getDaysRemaining())
            .status(certificate.getStatus() != null ? certificate.getStatus().name() : null)
            .versionNumber(certificate.getVersionNumber())
            .isCurrentVersion(certificate.getIsCurrentVersion())
            .isActive(certificate.getIsActive())
            .isRevoked(certificate.getIsRevoked())
            .revokedAt(certificate.getRevokedAt())
            .revokedBy(certificate.getRevokedBy())
            .revokedReason(certificate.getRevokedReason())
            .description(certificate.getDescription())
            .tags(certificate.getTags())
            .notificationEmails(certificate.getNotificationEmails())
            .createdDate(certificate.getCreatedDate())
            .createdBy(certificate.getCreatedBy())
            .lastModifiedDate(certificate.getLastModifiedDate())
            .lastModifiedBy(certificate.getLastModifiedBy())
            .build();
    }
}
