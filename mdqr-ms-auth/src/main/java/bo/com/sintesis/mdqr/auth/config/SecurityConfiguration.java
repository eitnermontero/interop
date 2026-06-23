package bo.com.sintesis.mdqr.auth.config;

import bo.com.sintesis.mdqr.auth.web.rest.errors.ErrorCode;
import bo.com.sintesis.mdqr.auth.web.rest.errors.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final ProblemWriter problemWriter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/management/health/**", "/management/info", "/error").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(
                    "/admin/auth/login",
                    "/admin/auth/refresh",
                    "/admin/auth/logout"
                ).permitAll()
                // Keycloak Event Listener webhook authenticates via the X-Keycloak-Secret
                // header checked inside the controller, not via JWT.
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    "/admin/audit/keycloak"
                ).permitAll()
                // Audit ingestion (POST /admin/audit) requires a service-account JWT
                // with SCOPE_audit:write, enforced via @PreAuthorize on the controller.
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(this::writeUnauthenticated)
                .accessDeniedHandler(this::writeForbidden)
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
                .authenticationEntryPoint(this::writeUnauthenticated)
                .accessDeniedHandler(this::writeForbidden)
            );
        return http.build();
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.forLanguageTag("es")));
        return resolver;
    }

    private void writeUnauthenticated(HttpServletRequest req, HttpServletResponse res,
                                      AuthenticationException ex) throws IOException {
        problemWriter.write(res, problemWriter.problem(ErrorCode.UNAUTHENTICATED));
    }

    private void writeForbidden(HttpServletRequest req, HttpServletResponse res,
                                AccessDeniedException ex) throws IOException {
        problemWriter.write(res, problemWriter.problem(ErrorCode.FORBIDDEN));
    }
}
