package bo.com.sintesis.mdqr.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAspectTest {

    @Mock
    private AuditEventPublisher publisher;

    @Mock
    private AuditContextExtractor contextExtractor;

    @Mock
    private ProceedingJoinPoint pjp;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private Auditable auditable;

    @Mock
    private Environment environment;

    private AuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AuditAspect(publisher, contextExtractor, "test-service", environment);
        when(auditable.event()).thenReturn("CREATE");
        when(auditable.module()).thenReturn("USUARIOS");
        when(auditable.option()).thenReturn("");
        when(auditable.includeRequestBody()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void tenantId_populated_from_env() throws Throwable {
        when(environment.getProperty("TENANT_ID")).thenReturn("alpha");
        when(pjp.proceed()).thenReturn(ResponseEntity.ok().build());

        aspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("alpha");
    }

    @Test
    void tenantId_null_when_env_not_set() throws Throwable {
        when(environment.getProperty("TENANT_ID")).thenReturn(null);
        when(pjp.proceed()).thenReturn(ResponseEntity.ok().build());

        aspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().tenantId()).isNull();
    }

    @Test
    void reqId_taken_from_mdc() throws Throwable {
        MDC.put("requestId", "abc-123");
        when(pjp.proceed()).thenReturn(ResponseEntity.ok().build());

        aspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().reqId()).isEqualTo("abc-123");
    }

    @Test
    void reqId_null_when_mdc_empty() throws Throwable {
        when(pjp.proceed()).thenReturn(ResponseEntity.ok().build());

        aspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().reqId()).isNull();
    }

    @Test
    void responseStatus_201_from_created_ResponseEntity() throws Throwable {
        when(pjp.proceed()).thenReturn(ResponseEntity.created(java.net.URI.create("/x/1")).build());

        aspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().responseStatus()).isEqualTo(201);
    }

    @Test
    void responseStatus_uses_HttpStatusResolver_on_exception() throws Throwable {
        when(pjp.proceed()).thenThrow(new BadRequestException());

        assertThatThrownBy(() -> aspect.audit(pjp, auditable))
            .isInstanceOf(BadRequestException.class);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().responseStatus()).isEqualTo(400);
    }

    @Test
    void responseStatus_500_for_unmapped_exception() throws Throwable {
        when(pjp.proceed()).thenThrow(new RuntimeException("unexpected"));

        assertThatThrownBy(() -> aspect.audit(pjp, auditable))
            .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<AuditEventDto> captor = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().responseStatus()).isEqualTo(500);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class BadRequestException extends RuntimeException {
        BadRequestException() { super("bad request"); }
    }
}
