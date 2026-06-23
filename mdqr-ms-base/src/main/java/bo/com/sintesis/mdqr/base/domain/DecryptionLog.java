package bo.com.sintesis.mdqr.base.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "decode_log")
@Getter
@Setter
public class DecryptionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "decode_log_seq")
    @SequenceGenerator(name = "decode_log_seq", sequenceName = "decode_log_seq", allocationSize = 1)
    private Long id;

    @Column(name = "log_id", nullable = false, unique = true, length = 60)
    private String logId;

    @Column(name = "keycloak_client_id", nullable = false, length = 255)
    private String keycloakClientId;

    @Column(name = "mtls_cert_cn", nullable = false, length = 255)
    private String mtlsCertCn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certificate_id")
    private Certificate certificate;

    @Column(name = "qr_string_hash", nullable = false, length = 64)
    private String qrStringHash;

    @Column(name = "entity_id_request", length = 50)
    private String entityIdRequest;

    @Column(name = "external_reference", length = 200)
    private String externalReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "qr_type", length = 20)
    private String qrType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decrypted_data_json", columnDefinition = "jsonb")
    private String decryptedDataJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_date", nullable = false)
    private Instant createdDate;
}
