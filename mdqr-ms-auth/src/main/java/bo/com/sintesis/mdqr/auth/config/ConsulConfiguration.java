package bo.com.sintesis.mdqr.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.consul.ConsulAutoConfiguration;
import org.springframework.cloud.consul.ConsulClient;
import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(value = "spring.cloud.consul.enabled", matchIfMissing = true)
public class ConsulConfiguration {

    @Bean
    @Primary
    public ConsulClient primaryConsulClient(ConsulProperties properties) {
        return ConsulAutoConfiguration.createNewConsulClient(properties);
    }
}
