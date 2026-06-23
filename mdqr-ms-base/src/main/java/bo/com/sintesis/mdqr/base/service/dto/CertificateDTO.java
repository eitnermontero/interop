package bo.com.sintesis.mdqr.base.service.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO para información de un certificado público.
 * Usado para respuestas de API y listados.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertificateDTO {

    /**
     * ID interno del certificado en la base de datos.
     */
    private Long id;

    /**
     * Serial number del certificado (hex lowercase).
     */
    private String serialNumber;

    /**
     * SHA-256 fingerprint del certificado.
     */
    private String fingerprintSha256;

    /**
     * ID de la entidad bancaria.
     * Ej: "1017" (Banco Sol), "1001" (BCP)
     */
    private String entityId;

    /**
     * Nombre de la entidad bancaria.
     */
    private String entityName;

    /**
     * Subject DN del certificado.
     * Ej: "CN=BANCOSOL, O=Banco Sol, C=BO"
     */
    private String subjectDn;

    /**
     * Issuer DN del certificado.
     */
    private String issuerDn;

    /**
     * Issuer CN (Common Name).
     */
    private String issuerCn;

    /**
     * Fecha desde la cual el certificado es válido.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant validFrom;

    /**
     * Fecha hasta la cual el certificado es válido.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant validTo;

    /**
     * Días restantes hasta la expiración.
     */
    private Long daysRemaining;

    /**
     * Estado del certificado.
     * Valores: ACTIVE, EXPIRING_SOON, EXPIRED, REVOKED, SUPERSEDED
     */
    private String status;

    /**
     * Número de versión.
     */
    private Integer versionNumber;

    /**
     * Si es la versión actual.
     */
    private Boolean isCurrentVersion;

    /**
     * Si está activo.
     */
    private Boolean isActive;

    /**
     * Si está revocado.
     */
    private Boolean isRevoked;

    /**
     * Fecha de revocación.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant revokedAt;

    /**
     * Usuario que revocó.
     */
    private String revokedBy;

    /**
     * Razón de revocación.
     */
    private String revokedReason;

    /**
     * Descripción del certificado.
     */
    private String description;

    /**
     * Tags del certificado.
     */
    private String[] tags;

    /**
     * Emails para notificaciones.
     */
    private String[] notificationEmails;

    /**
     * Fecha de creación del registro.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant createdDate;

    /**
     * Usuario que creó el registro.
     */
    private String createdBy;

    /**
     * Última modificación.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant lastModifiedDate;

    /**
     * Usuario que modificó.
     */
    private String lastModifiedBy;
}
