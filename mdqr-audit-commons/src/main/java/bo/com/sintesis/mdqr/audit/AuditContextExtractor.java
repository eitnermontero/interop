package bo.com.sintesis.mdqr.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

/**
 * Populates an {@link AuditEventDto.AuditEventDtoBuilder} with data pulled from
 * the current request and security context.
 *
 * Servlet-only. Reactive consumers (mwc-gateway) should not depend on this class.
 */
public class AuditContextExtractor {

    public void populate(AuditEventDto.AuditEventDtoBuilder builder) {
        populateUser(builder);
        populateRequest(builder);
    }

    private void populateUser(AuditEventDto.AuditEventDtoBuilder builder) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return;
        Jwt jwt = jwtAuth.getToken();
        builder.userId(jwt.getSubject())
               .username(jwt.getClaimAsString("preferred_username"))
               .fullName(buildFullName(jwt))
               .roles(extractRealmRoles(jwt));
    }

    private void populateRequest(AuditEventDto.AuditEventDtoBuilder builder) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest req = attrs.getRequest();
        builder.ipAddress(resolveIp(req))
               .userAgent(req.getHeader("User-Agent"))
               .httpMethod(req.getMethod())
               .endpoint(req.getRequestURI());
    }

    private String resolveIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = req.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : req.getRemoteAddr();
    }

    private String buildFullName(Jwt jwt) {
        String full = jwt.getClaimAsString("name");
        if (full != null && !full.isBlank()) return full;
        String given  = nullToEmpty(jwt.getClaimAsString("given_name"));
        String family = nullToEmpty(jwt.getClaimAsString("family_name"));
        String combined = (given + " " + family).trim();
        return combined.isEmpty() ? jwt.getClaimAsString("preferred_username") : combined;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRealmRoles(Jwt jwt) {
        Object claim = jwt.getClaim("realm_access");
        if (claim instanceof Map<?, ?> realmAccess) {
            Object roles = realmAccess.get("roles");
            if (roles instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }
        return List.of();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
