package bo.com.sintesis.mdqr.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_seq")
    @SequenceGenerator(name = "audit_log_seq", sequenceName = "audit_log_seq", allocationSize = 50)
    private Long id;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "module", nullable = false, length = 100)
    private String module;

    @Column(name = "option_code", length = 100)
    private String optionCode;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "roles", columnDefinition = "text[]")
    private String[] roles = new String[0];

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "service_name", nullable = false, length = 50)
    private String serviceName;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "endpoint", length = 255)
    private String endpoint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    @Column(name = "req_id", length = 128)
    private String reqId;

    @Column(name = "created_date", insertable = false, updatable = false)
    private Instant createdDate;
}
