package bo.com.sintesis.mdqr.audit.config;

import bo.com.sintesis.mdqr.audit.AuditAspect;
import bo.com.sintesis.mdqr.audit.AuditClient;
import bo.com.sintesis.mdqr.audit.AuditContextExtractor;
import bo.com.sintesis.mdqr.audit.AuditEventPublisher;
import bo.com.sintesis.mdqr.audit.AuditEventSink;
import bo.com.sintesis.mdqr.audit.RemoteHttpSink;
import bo.com.sintesis.mdqr.audit.ServiceTokenProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
@ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditAutoConfiguration {

    // --- remote mode beans (default) ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit", name = "sink-mode", havingValue = "remote", matchIfMissing = true)
    public ServiceTokenProvider auditServiceTokenProvider(AuditProperties props) {
        AuditProperties.OAuth oauth = props.oauth();
        if (oauth == null || oauth.tokenUri() == null || oauth.tokenUri().isBlank()) {
            throw new IllegalStateException(
                "Missing required property: audit.oauth.token-uri");
        }
        return new ServiceTokenProvider(
            RestClient.builder().build(),
            oauth.tokenUri(),
            oauth.clientId(),
            oauth.clientSecret(),
            oauth.scope());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit", name = "sink-mode", havingValue = "remote", matchIfMissing = true)
    public AuditClient auditClient(AuditProperties props, ServiceTokenProvider tokenProvider) {
        String endpoint = props.endpoint() != null && !props.endpoint().isBlank()
            ? props.endpoint()
            : "http://localhost:8083/admin/audit";
        return new AuditClient(RestClient.builder().build(), endpoint, tokenProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit", name = "sink-mode", havingValue = "remote", matchIfMissing = true)
    public AuditEventSink remoteHttpSink(AuditClient client) {
        return new RemoteHttpSink(client);
    }

    // --- publisher (common to all modes) ---

    @Bean
    @ConditionalOnMissingBean
    public AuditEventPublisher auditEventPublisher(AuditEventSink sink,
                                                   AuditProperties props,
                                                   ObjectProvider<MeterRegistry> meterRegistryProvider) {
        int bufferSize  = props.bufferSize()    != null ? props.bufferSize()    : 10_000;
        int maxAttempts = props.retry() != null && props.retry().maxAttempts() != null
            ? props.retry().maxAttempts() : 3;
        long backoffMs  = props.retry() != null && props.retry().backoffMs() != null
            ? props.retry().backoffMs() : 2_000L;
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        return new AuditEventPublisher(sink, bufferSize, maxAttempts, backoffMs, registry);
    }

    /**
     * Aspect activation requires servlet API in the consumer's classpath.
     * Reactive consumers (gateway) only get the publisher and call it directly.
     */
    @Configuration
    @ConditionalOnClass(HttpServletRequest.class)
    public static class ServletAuditConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AuditContextExtractor auditContextExtractor() {
            return new AuditContextExtractor();
        }

        @Bean
        @ConditionalOnMissingBean
        public AuditAspect auditAspect(AuditEventPublisher publisher,
                                       AuditContextExtractor extractor,
                                       Environment env) {
            String serviceName = env.getProperty("spring.application.name", "unknown");
            return new AuditAspect(publisher, extractor, serviceName, env);
        }
    }
}
