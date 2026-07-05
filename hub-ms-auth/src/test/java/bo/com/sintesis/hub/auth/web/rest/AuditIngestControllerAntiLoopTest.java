package bo.com.sintesis.hub.auth.web.rest;

import bo.com.sintesis.hub.audit.Auditable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: AuditIngestController must NEVER carry @Auditable on any method.
 * Annotating the ingest endpoint would cause an infinite self-audit loop because the
 * in-process sink calls AuditLogService which is triggered by AuditAspect on the same call.
 */
class AuditIngestControllerAntiLoopTest {

    @Test
    void auditIngestController_has_no_auditable_annotation_on_class() {
        assertThat(AuditIngestController.class.isAnnotationPresent(Auditable.class))
            .as("AuditIngestController must not carry @Auditable at class level (would cause audit loop)")
            .isFalse();
    }

    @Test
    void auditIngestController_has_no_auditable_annotation_on_any_method() {
        for (Method method : AuditIngestController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Auditable.class))
                .as("Method '%s' in AuditIngestController must not carry @Auditable (would cause audit loop)",
                    method.getName())
                .isFalse();
        }
    }
}
