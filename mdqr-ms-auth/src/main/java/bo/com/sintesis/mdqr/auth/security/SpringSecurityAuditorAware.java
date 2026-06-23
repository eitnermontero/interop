package bo.com.sintesis.mdqr.auth.security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("springSecurityAuditorAware")
public class SpringSecurityAuditorAware implements AuditorAware<String> {

    static final String SYSTEM_ACCOUNT = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.of(SYSTEM_ACCOUNT);
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String preferred = jwt.getClaimAsString("preferred_username");
            if (preferred != null && !preferred.isBlank()) {
                return Optional.of(preferred);
            }
            String subject = jwt.getSubject();
            return Optional.ofNullable(subject)
                    .filter(s -> !s.isBlank())
                    .or(() -> Optional.of(SYSTEM_ACCOUNT));
        }

        return Optional.of(SYSTEM_ACCOUNT);
    }
}
