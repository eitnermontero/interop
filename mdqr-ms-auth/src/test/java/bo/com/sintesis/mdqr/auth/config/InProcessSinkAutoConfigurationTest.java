package bo.com.sintesis.mdqr.auth.config;

import bo.com.sintesis.mdqr.auth.service.AuditLogService;
import bo.com.sintesis.mdqr.audit.AuditClient;
import bo.com.sintesis.mdqr.audit.AuditEventSink;
import bo.com.sintesis.mdqr.audit.ServiceTokenProvider;
import bo.com.sintesis.mdqr.audit.config.AuditAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that with audit.sink-mode=in-process the auto-configuration does NOT
 * load AuditClient or ServiceTokenProvider, and that InProcessSink is the active sink.
 */
class InProcessSinkAutoConfigurationTest {

    private final AuditLogService mockService = mock(AuditLogService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
        .withPropertyValues("audit.sink-mode=in-process")
        .withBean("auditLogService", AuditLogService.class, () -> mockService)
        .withBean("inProcessSink", InProcessSink.class, () -> new InProcessSink(mockService));

    @Test
    void no_AuditClient_in_context_when_in_process_mode() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(AuditClient.class);
        });
    }

    @Test
    void no_ServiceTokenProvider_in_context_when_in_process_mode() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ServiceTokenProvider.class);
        });
    }

    @Test
    void InProcessSink_is_the_registered_AuditEventSink() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(AuditEventSink.class);
            assertThat(ctx.getBean(AuditEventSink.class)).isInstanceOf(InProcessSink.class);
        });
    }
}
