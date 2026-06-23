package bo.com.sintesis.mdqr.base.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Configuración de seguridad con OAuth2 JWT.
 * <p>
 * Implementa:
 * - OAuth2 Resource Server con validación JWT de Keycloak
 * - Role-Based Access Control (RBAC) con roles: API_CLIENT, ADMIN, AUDITOR
 * - CORS configuration
 * - Stateless sessions
 * </p>
 */
@Configuration
@EnableWebSecurity
// TODO: Habilitar para producción con OAuth2
// @EnableMethodSecurity(prePostEnabled = true)
@Slf4j
public class SecurityConfiguration {

    /**
     * Configura el filtro de seguridad HTTP.
     *
     * @param http HttpSecurity builder
     * @return SecurityFilterChain configurado
     * @throws Exception si hay error en configuración
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configurando SecurityFilterChain - MODO DESARROLLO (sin OAuth2)");
        log.warn("⚠️ SEGURIDAD DESHABILITADA - Solo para desarrollo local");
        log.warn("⚠️ TODO: Habilitar OAuth2 JWT para producción");

        http
            // TODO: Descomentar para habilitar OAuth2 en producción
            // .oauth2ResourceServer(oauth2 -> oauth2
            //     .jwt(jwt -> jwt
            //         .jwtAuthenticationConverter(jwtAuthenticationConverter())
            //     )
            // )

            // Reglas de autorización - TEMPORALMENTE PERMISIVAS PARA DESARROLLO
            .authorizeHttpRequests(authz -> authz
                // Endpoints públicos
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // TODO: En producción, estos endpoints requerirán roles específicos
                // .requestMatchers("/api/qr/decrypt").hasRole("API_CLIENT")
                // .requestMatchers("/api/qr/audits/**").hasAnyRole("ADMIN", "AUDITOR")
                // .requestMatchers("/api/certificates/**").hasRole("ADMIN")

                // TEMPORAL: Permitir todos los requests para testing local
                .anyRequest().permitAll()
            )

            // Configuración de sesión stateless
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Deshabilitar CSRF (API REST stateless)
            .csrf(AbstractHttpConfigurer::disable)

            // Configuración CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        log.info("SecurityFilterChain configurado - Modo desarrollo sin autenticación");

        return http.build();
    }

    /**
     * Convierte el JWT en un token de autenticación con roles de Keycloak.
     * Extrae los roles de realm_access.roles y los convierte en GrantedAuthorities con prefijo ROLE_.
     *
     * @return JwtAuthenticationConverter configurado
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * Configuración CORS para permitir requests desde frontend.
     *
     * @return CorsConfigurationSource configurado
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Permitir orígenes (configurar según ambiente)
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "https://*.sintesis.com.bo"));

        // Permitir métodos HTTP
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Permitir headers
        configuration.setAllowedHeaders(List.of("*"));

        // Permitir credentials
        configuration.setAllowCredentials(true);

        // Headers expuestos al cliente
        configuration.setExposedHeaders(List.of("Authorization", "X-Request-Id", "X-Total-Count"));

        // Max age del preflight request
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        log.info("CORS configurado para orígenes: {}", configuration.getAllowedOriginPatterns());

        return source;
    }

    /**
     * Converter personalizado para extraer roles de Keycloak del JWT.
     * Los roles se extraen de realm_access.roles y se convierten en GrantedAuthorities.
     */
    public static class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            // Extraer realm_access del JWT
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");

            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                log.debug("No se encontraron roles en realm_access del JWT");
                return List.of();
            }

            // Extraer lista de roles
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");

            if (roles == null || roles.isEmpty()) {
                log.debug("Lista de roles vacía en JWT");
                return List.of();
            }

            // Convertir a GrantedAuthorities con prefijo ROLE_
            Collection<GrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());

            log.debug("Roles extraídos del JWT: {}", roles);

            return authorities;
        }
    }
}
