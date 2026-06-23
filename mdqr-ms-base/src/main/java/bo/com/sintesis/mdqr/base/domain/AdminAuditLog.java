package bo.com.sintesis.mdqr.base.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
public class AdminAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "admin_audit_log_seq")
    @SequenceGenerator(name = "admin_audit_log_seq", sequenceName = "admin_audit_log_seq", allocationSize = 1)
    private Long id;

    @Column(name = "keycloak_user_id", length = 255)
    private String keycloakUserId;

    @Column(name = "keycloak_username", length = 255)
    private String keycloakUsername;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
