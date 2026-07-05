package bo.com.sintesis.hub.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class CorsConfiguration {

    private final ApplicationProperties properties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var cors = properties.cors();
        var source = new UrlBasedCorsConfigurationSource();
        var config = new org.springframework.web.cors.CorsConfiguration();

        config.setAllowedOrigins(cors.allowedOrigins());
        config.setAllowedMethods(List.of(cors.allowedMethods().split(",")));
        config.setAllowedHeaders(List.of(cors.allowedHeaders().split(",")));
        config.setExposedHeaders(List.of(cors.exposedHeaders().split(",")));
        config.setAllowCredentials(cors.allowCredentials());
        config.setMaxAge(cors.maxAge());

        source.registerCorsConfiguration("/services/**", config);
        source.registerCorsConfiguration("/v3/api-docs/**", config);
        source.registerCorsConfiguration("/swagger-ui/**", config);

        return source;
    }
}
