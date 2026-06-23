package bo.com.sintesis.mdqr.base.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Configuración de JPA Auditing.
 * <p>
 * Proporciona el usuario actual para los campos @CreatedBy y @LastModifiedBy.
 * En desarrollo sin autenticación, usa "system" como fallback.
 * </p>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@Slf4j
public class AuditingConfiguration {

    /**
     * Bean que proporciona el usuario actual para JPA Auditing.
     *
     * @return AuditorAware que retorna el username del usuario autenticado o "system"
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return new AuditorAwareImpl();
    }

    /**
     * Implementación de AuditorAware que obtiene el usuario del SecurityContext.
     * Si no hay usuario autenticado, retorna "system" como fallback.
     */
    public static class AuditorAwareImpl implements AuditorAware<String> {

        @Override
        public Optional<String> getCurrentAuditor() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                log.trace("No hay usuario autenticado - usando 'system' como auditor");
                return Optional.of("system");
            }

            String principal = authentication.getName();
            if (principal == null || principal.equals("anonymousUser")) {
                log.trace("Usuario anónimo - usando 'system' como auditor");
                return Optional.of("system");
            }

            log.trace("Usuario autenticado: {} - usando como auditor", principal);
            return Optional.of(principal);
        }
    }
}
