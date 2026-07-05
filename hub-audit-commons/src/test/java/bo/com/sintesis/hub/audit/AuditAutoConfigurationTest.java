package bo.com.sintesis.hub.audit;

import bo.com.sintesis.hub.audit.config.AuditAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6.4 - Verifies AuditAutoConfiguration sink-mode branching.
 *
 * remote (default): RemoteHttpSink + AuditClient + ServiceTokenProvider loaded.
 * in-process: none of the above loaded; host must register its own AuditEventSink.
 */
class AuditAutoConfigurationTest {

    private static final String TOKEN_URI = "http://keycloak.test/token";
    private static final String CLIENT_ID = "audit-client";
    private static final String CLIENT_SECRET = "secret";
    private static final String SCOPE = "audit:write";

    private final ApplicationContextRunner remoteRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
        .withPropertyValues(
            "audit.sink-mode=remote",
            "audit.oauth.token-uri=" + TOKEN_URI,
            "audit.oauth.client-id=" + CLIENT_ID,
            "audit.oauth.client-secret=" + CLIENT_SECRET,
            "audit.oauth.scope=" + SCOPE
        );

    private final ApplicationContextRunner inProcessRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
        .withPropertyValues("audit.sink-mode=in-process")
        .withBean("stubSink", AuditEventSink.class, () -> event -> {});

    @Test
    void remote_mode_loads_RemoteHttpSink_as_active_sink() {
        remoteRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(AuditEventSink.class);
            assertThat(ctx.getBean(AuditEventSink.class)).isInstanceOf(RemoteHttpSink.class);
        });
    }

    @Test
    void remote_mode_loads_AuditClient() {
        remoteRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(AuditClient.class);
        });
    }

    @Test
    void remote_mode_loads_ServiceTokenProvider() {
        remoteRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ServiceTokenProvider.class);
        });
    }

    @Test
    void in_process_mode_does_not_load_AuditClient() {
        inProcessRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(AuditClient.class);
        });
    }

    @Test
    void in_process_mode_does_not_load_ServiceTokenProvider() {
        inProcessRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ServiceTokenProvider.class);
        });
    }

    @Test
    void in_process_mode_active_sink_is_the_stub_registered_by_host() {
        inProcessRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(AuditEventSink.class);
            assertThat(ctx.getBean(AuditEventSink.class)).isNotInstanceOf(RemoteHttpSink.class);
        });
    }

    @Test
    void default_mode_without_explicit_sink_mode_loads_RemoteHttpSink() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
            .withPropertyValues(
                "audit.oauth.token-uri=" + TOKEN_URI,
                "audit.oauth.client-id=" + CLIENT_ID,
                "audit.oauth.client-secret=" + CLIENT_SECRET
            )
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(AuditEventSink.class)).isInstanceOf(RemoteHttpSink.class);
            });
    }
}
