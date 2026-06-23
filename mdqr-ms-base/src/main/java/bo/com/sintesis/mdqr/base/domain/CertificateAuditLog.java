package bo.com.sintesis.mdqr.base.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Auditoría completa de operaciones sobre certificados.
 * Registra TODAS las acciones incluyendo desencriptación de QRs.
 */
@Entity
@Table(name = "certificate_audit_log", indexes = {
    @Index(name = "idx_audit_cert", columnList = "certificate_id"),
    @Index(name = "idx_audit_serial", columnList = "serial_number"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_user", columnList = "user_id"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
@Getter
@Setter
public class CertificateAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "certificate_audit_log_seq")
    @SequenceGenerator(name = "certificate_audit_log_seq", sequenceName = "certificate_audit_log_id_seq", allocationSize = 1)
    private Long id;

    // ============================================================
    // Referencia al certificado
    // ============================================================

    @Column(name = "certificate_id")
    private Long certificateId;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    // ============================================================
    // Acción realizada
    // ============================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private AuditAction action;

    // ============================================================
    // Actor
    // ============================================================

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Lob
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    // ============================================================
    // Timestamp
    // ============================================================

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp = Instant.now();

    // ============================================================
    // Estados antes/después (JSON)
    // ============================================================

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    // ============================================================
    // Resultado
    // ============================================================

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    // ============================================================
    // Metadata adicional
    // ============================================================

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "entity_id_request", length = 50)
    private String entityIdRequest;

    @Column(name = "qr_content_hash", length = 64)
    private String qrContentHash;

    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;

    // ============================================================
    // Enums
    // ============================================================

    public enum AuditAction {
        /**
         * Subir nuevo certificado
         */
        UPLOAD,

        /**
         * Validar certificado (sin guardar)
         */
        VALIDATE,

        /**
         * Activar certificado
         */
        ACTIVATE,

        /**
         * Desactivar certificado
         */
        DEACTIVATE,

        /**
         * Revocar certificado
         */
        REVOKE,

        /**
         * Reemplazar certificado (nueva versión)
         */
        REPLACE,

        /**
         * Rollback a versión anterior
         */
        ROLLBACK,

        /**
         * Ver detalle de certificado
         */
        VIEW,

        /**
         * Descargar certificado PEM
         */
        DOWNLOAD,

        /**
         * Buscar certificados
         */
        SEARCH,

        /**
         * Desencriptar QR (crítico para auditoría de pagos)
         */
        DECRYPT_QR
    }
}
