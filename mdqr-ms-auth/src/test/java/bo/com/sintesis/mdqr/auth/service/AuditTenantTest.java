package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.domain.AuditLog;
import bo.com.sintesis.mdqr.auth.repository.AuditLogRepository;
import bo.com.sintesis.mdqr.auth.service.dto.AuditEventRequest;
import bo.com.sintesis.mdqr.auth.service.dto.AuditLogDto;
import jakarta.persistence.Column;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 6.6 - Verifies tenant_id nullable handling:
 * - AuditLog.tenant_id column is defined as nullable in JPA.
 * - AuditLogService.record() with tenantId=null persists without error.
 * - AuditEventRequest with tenantId=null maps to AuditLog correctly.
 */
@ExtendWith(MockitoExtension.class)
class AuditTenantTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository);
    }

    @Test
    void AuditLog_tenant_id_column_is_nullable() throws Exception {
        Field field = AuditLog.class.getDeclaredField("tenantId");
        Column col = field.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        // JPA Column.nullable() defaults to true; if explicitly set to false it would fail null inserts.
        assertThat(col.nullable())
            .as("tenant_id column must be nullable to support single-tenant deployments")
            .isTrue();
    }

    @Test
    void record_with_null_tenantId_does_not_throw() {
        AuditLog saved = entityWith(null, null);
        when(auditLogRepository.save(any())).thenReturn(saved);

        AuditEventRequest req = request(null, null);

        assertThatNoException().isThrownBy(() -> auditLogService.record(req));
    }

    @Test
    void record_with_null_tenantId_persists_entity_with_tenantId_null() {
        AuditLog saved = entityWith(null, null);
        when(auditLogRepository.save(any())).thenReturn(saved);

        auditLogService.record(request(null, null));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isNull();
    }

    @Test
    void record_with_tenantId_persists_correct_value() {
        AuditLog saved = entityWith("alpha", "req-001");
        when(auditLogRepository.save(any())).thenReturn(saved);

        auditLogService.record(request("alpha", "req-001"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("alpha");
        assertThat(captor.getValue().getReqId()).isEqualTo("req-001");
    }

    @Test
    void record_maps_tenantId_and_reqId_to_dto() {
        AuditLog saved = entityWith("beta", "req-xyz");
        when(auditLogRepository.save(any())).thenReturn(saved);

        AuditLogDto dto = auditLogService.record(request("beta", "req-xyz"));

        assertThat(dto.tenantId()).isEqualTo("beta");
        assertThat(dto.reqId()).isEqualTo("req-xyz");
    }

    // -- helpers --

    private AuditEventRequest request(String tenantId, String reqId) {
        return new AuditEventRequest(
            Instant.now(), "CREATE", "USUARIOS",
            null, null, null, null, null,
            null, null, "mwc-admin-service",
            null, null, 200, null, null,
            tenantId, reqId
        );
    }

    private AuditLog entityWith(String tenantId, String reqId) {
        AuditLog e = new AuditLog();
        e.setEventTime(Instant.now());
        e.setEventType("CREATE");
        e.setModule("USUARIOS");
        e.setServiceName("mwc-admin-service");
        e.setResponseStatus(200);
        e.setTenantId(tenantId);
        e.setReqId(reqId);
        return e;
    }
}
