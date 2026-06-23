package bo.com.sintesis.mdqr.base.service.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO detallado de certificado que incluye el contenido PEM.
 * Usado para visualización y descarga de certificados.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertificateDetailDTO {

    /**
     * ID interno.
     */
    private Long id;

    /**
     * Serial number.
     */
    private String serialNumber;

    /**
     * SHA-256 fingerprint.
     */
    private String fingerprintSha256;

    /**
     * ID de la entidad bancaria.
     */
    private String entityId;

    /**
     * Nombre de la entidad.
     */
    private String entityName;

    /**
     * Contenido PEM completo del certificado.
     */
    private String pemContent;

    /**
     * Subject DN.
     */
    private String subjectDn;

    /**
     * Issuer DN.
     */
    private String issuerDn;

    /**
     * Issuer CN.
     */
    private String issuerCn;

    /**
     * Fecha inicio validez.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant validFrom;

    /**
     * Fecha fin validez.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant validTo;

    /**
     * Días restantes.
     */
    private Long daysRemaining;

    /**
     * Estado.
     */
    private String status;

    /**
     * Versión.
     */
    private Integer versionNumber;

    /**
     * Es versión actual.
     */
    private Boolean isCurrentVersion;

    /**
     * Está activo.
     */
    private Boolean isActive;

    /**
     * Está revocado.
     */
    private Boolean isRevoked;

    /**
     * Fecha revocación.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant revokedAt;

    /**
     * Usuario que revocó.
     */
    private String revokedBy;

    /**
     * Razón revocación.
     */
    private String revokedReason;

    /**
     * Descripción.
     */
    private String description;

    /**
     * Tags.
     */
    private String[] tags;

    /**
     * Emails notificación.
     */
    private String[] notificationEmails;

    /**
     * Fecha creación.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant createdDate;

    /**
     * Creado por.
     */
    private String createdBy;

    /**
     * Última modificación.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant lastModifiedDate;

    /**
     * Modificado por.
     */
    private String lastModifiedBy;
}
