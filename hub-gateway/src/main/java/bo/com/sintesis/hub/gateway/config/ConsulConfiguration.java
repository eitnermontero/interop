package bo.com.sintesis.hub.gateway.config;

import org.springframework.cloud.consul.ConsulAutoConfiguration;
import org.springframework.cloud.consul.ConsulClient;
import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ConsulConfiguration {

    @Bean
    @Primary
    public ConsulClient primaryConsulClient(ConsulProperties properties) {
        return ConsulAutoConfiguration.createNewConsulClient(properties);
    }
}
