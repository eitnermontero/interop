package bo.com.sintesis.hub.gateway.config;

import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

@Configuration
@EnableConfigurationProperties(DataRedisProperties.class)
public class RedisConfiguration {

    // Standalone (dev) o cluster (stage/prod) segun application.redis.cluster.enabled.
    // LettuceConnectionFactory implementa ReactiveRedisConnectionFactory, asi que sirve
    // para los filtros reactivos y spring.session del gateway. Sin pool (reactive single-conn).
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            DataRedisProperties redisProperties,
            ApplicationProperties applicationProperties) {

        boolean clusterEnabled = applicationProperties.redis().cluster().enabled();
        List<String> clusterNodes = applicationProperties.redis().cluster().nodes();

        var clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(redisProperties.getTimeout())
            .build();

        if (clusterEnabled) {
            var cfg = new RedisClusterConfiguration(clusterNodes);
            if (redisProperties.getPassword() != null) cfg.setPassword(redisProperties.getPassword());
            return new LettuceConnectionFactory(cfg, clientConfig);
        }
        var cfg = new RedisStandaloneConfiguration(redisProperties.getHost(), redisProperties.getPort());
        if (redisProperties.getPassword() != null) cfg.setPassword(redisProperties.getPassword());
        return new LettuceConnectionFactory(cfg, clientConfig);
    }
}
