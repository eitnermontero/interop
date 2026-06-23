package bo.com.sintesis.mdqr.base.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Historial de versiones de certificados.
 * Preserva snapshots de certificados reemplazados.
 */
@Entity
@Table(name = "certificate_version", indexes = {
    @Index(name = "idx_certver_cert", columnList = "certificate_id"),
    @Index(name = "idx_certver_replaced", columnList = "replaced_at")
})
@Getter
@Setter
public class CertificateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "certificate_version_seq")
    @SequenceGenerator(name = "certificate_version_seq", sequenceName = "certificate_version_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "certificate_id", nullable = false)
    private Long certificateId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    // ============================================================
    // Snapshot de la versión anterior
    // ============================================================

    @Lob
    @Column(name = "pem_content_snapshot", nullable = false, columnDefinition = "TEXT")
    private String pemContentSnapshot;

    @Column(name = "fingerprint_sha256_snapshot", nullable = false, length = 64)
    private String fingerprintSha256Snapshot;

    @Column(name = "valid_from_snapshot", nullable = false)
    private Instant validFromSnapshot;

    @Column(name = "valid_to_snapshot", nullable = false)
    private Instant validToSnapshot;

    @Column(name = "subject_dn_snapshot", length = 500)
    private String subjectDnSnapshot;

    @Column(name = "issuer_dn_snapshot", length = 500)
    private String issuerDnSnapshot;

    // ============================================================
    // Metadata del cambio
    // ============================================================

    @Column(name = "replaced_at", nullable = false)
    private Instant replacedAt = Instant.now();

    @Column(name = "replaced_by", nullable = false, length = 100)
    private String replacedBy;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "change_type", length = 50)
    private String changeType;

    public enum ChangeType {
        RENEWAL,      // Renovación programada
        UPDATE,       // Actualización/corrección
        REPLACEMENT,  // Reemplazo urgente
        REVOCATION    // Revocación
    }
}
