package bo.com.sintesis.mdqr.base.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * Entidad que representa un certificado público de un banco.
 * Almacena el contenido PEM completo para desencriptar QRs.
 */
@Entity
@Table(name = "certificate", indexes = {
    @Index(name = "idx_cert_serial", columnList = "serial_number"),
    @Index(name = "idx_cert_fingerprint", columnList = "fingerprint_sha256"),
    @Index(name = "idx_cert_entity", columnList = "entity_id"),
    @Index(name = "idx_cert_status", columnList = "status")
})
@Getter
@Setter
public class Certificate extends AbstractAuditingEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "certificate_seq")
    @SequenceGenerator(name = "certificate_seq", sequenceName = "certificate_seq", allocationSize = 1)
    private Long id;

    // ============================================================
    // Identificación
    // ============================================================

    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;

    @Column(name = "fingerprint_sha256", nullable = false, unique = true, length = 64)
    private String fingerprintSha256;

    // ============================================================
    // Entidad/Banco
    // ============================================================

    @Column(name = "entity_id", nullable = false, length = 50)
    private String entityId;

    @Column(name = "entity_name", length = 200)
    private String entityName;

    // ============================================================
    // Contenido PEM completo
    // ============================================================

    @Lob
    @Column(name = "pem_content", nullable = false, columnDefinition = "TEXT")
    private String pemContent;

    // ============================================================
    // Metadata del certificado
    // ============================================================

    @Column(name = "subject_dn", nullable = false, length = 500)
    private String subjectDn;

    @Column(name = "issuer_dn", nullable = false, length = 500)
    private String issuerDn;

    @Column(name = "issuer_cn", length = 200)
    private String issuerCn;

    // ============================================================
    // Validez temporal
    // ============================================================

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    // ============================================================
    // Estado del certificado
    // ============================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CertificateStatus status = CertificateStatus.ACTIVE;

    // ============================================================
    // Versionamiento
    // ============================================================

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber = 1;

    @Column(name = "is_current_version", nullable = false)
    private Boolean isCurrentVersion = true;

    // ============================================================
    // Control operacional
    // ============================================================

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_revoked", nullable = false)
    private Boolean isRevoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    @Column(name = "revoked_reason", length = 500)
    private String revokedReason;

    // ============================================================
    // Información adicional
    // ============================================================

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "tags", columnDefinition = "varchar(255)[]")
    private String[] tags;

    @Column(name = "notification_emails", columnDefinition = "text[]")
    private String[] notificationEmails;

    // ============================================================
    // Enums
    // ============================================================

    public enum CertificateStatus {
        /**
         * Certificado activo y válido
         */
        ACTIVE,

        /**
         * Certificado expira en 30 días o menos
         */
        EXPIRING_SOON,

        /**
         * Certificado expirado
         */
        EXPIRED,

        /**
         * Certificado revocado manualmente
         */
        REVOKED,

        /**
         * Certificado reemplazado por una versión más reciente
         */
        SUPERSEDED
    }

    // ============================================================
    // Métodos de utilidad
    // ============================================================

    /**
     * Calcula los días restantes hasta la expiración
     */
    public long getDaysRemaining() {
        if (validTo == null) {
            return 0;
        }
        return java.time.Duration.between(Instant.now(), validTo).toDays();
    }

    /**
     * Verifica si el certificado está expirado
     */
    public boolean isExpired() {
        return validTo != null && validTo.isBefore(Instant.now());
    }

    /**
     * Verifica si el certificado está por expirar (30 días o menos)
     */
    public boolean isExpiringSoon() {
        if (validTo == null) {
            return false;
        }
        long daysRemaining = getDaysRemaining();
        return daysRemaining > 0 && daysRemaining <= 30;
    }
}
