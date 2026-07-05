package bo.com.sintesis.hub.auth.config;

import bo.com.sintesis.hub.auth.service.AuditLogService;
import bo.com.sintesis.hub.auth.service.dto.AuditEventRequest;
import bo.com.sintesis.hub.audit.AuditEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InProcessSinkTest {

    @Mock
    private AuditLogService auditLogService;

    private InProcessSink sink;

    @BeforeEach
    void setUp() {
        sink = new InProcessSink(auditLogService);
    }

    @Test
    void emit_delegates_to_auditLogService_record() {
        AuditEventDto event = AuditEventDto.builder()
            .eventTime(Instant.parse("2024-01-01T12:00:00Z"))
            .eventType("CREATE")
            .module("USUARIOS")
            .optionCode("CREAR_USUARIO")
            .userId("user-1")
            .username("admin")
            .fullName("Admin User")
            .roles(List.of("ADMIN"))
            .ipAddress("127.0.0.1")
            .userAgent("Mozilla/5.0")
            .serviceName("mwc-admin-service")
            .httpMethod("POST")
            .endpoint("/admin/users")
            .responseStatus(201)
            .durationMs(45)
            .details(Map.of("key", "value"))
            .tenantId("alpha")
            .reqId("req-abc-123")
            .build();

        sink.emit(event);

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditLogService).record(captor.capture());

        AuditEventRequest req = captor.getValue();
        assertThat(req.eventTime()).isEqualTo(event.eventTime());
        assertThat(req.eventType()).isEqualTo("CREATE");
        assertThat(req.module()).isEqualTo("USUARIOS");
        assertThat(req.optionCode()).isEqualTo("CREAR_USUARIO");
        assertThat(req.userId()).isEqualTo("user-1");
        assertThat(req.username()).isEqualTo("admin");
        assertThat(req.fullName()).isEqualTo("Admin User");
        assertThat(req.roles()).containsExactly("ADMIN");
        assertThat(req.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(req.serviceName()).isEqualTo("mwc-admin-service");
        assertThat(req.httpMethod()).isEqualTo("POST");
        assertThat(req.endpoint()).isEqualTo("/admin/users");
        assertThat(req.responseStatus()).isEqualTo(201);
        assertThat(req.durationMs()).isEqualTo(45);
        assertThat(req.tenantId()).isEqualTo("alpha");
        assertThat(req.reqId()).isEqualTo("req-abc-123");
    }

    @Test
    void emit_maps_null_roles_to_empty_list() {
        AuditEventDto event = AuditEventDto.builder()
            .eventTime(Instant.now())
            .eventType("DELETE")
            .module("USUARIOS")
            .serviceName("mwc-admin-service")
            .roles(null)
            .build();

        sink.emit(event);

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().roles()).isEmpty();
    }
}
