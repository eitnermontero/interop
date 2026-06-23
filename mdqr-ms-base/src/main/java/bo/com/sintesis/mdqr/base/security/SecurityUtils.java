package bo.com.sintesis.mdqr.base.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utilidades para obtener información del usuario autenticado desde el SecurityContext.
 * <p>
 * Extrae información del JWT de Keycloak:
 * - Username (claim "preferred_username")
 * - Client ID (claim "azp" - authorized party)
 * - Roles (de realm_access.roles)
 * </p>
 */
@Slf4j
public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class - no se puede instanciar
    }

    /**
     * Obtiene el username del usuario autenticado actual.
     * Extrae el claim "preferred_username" del JWT.
     *
     * @return Optional con el username si está presente
     */
    public static Optional<String> getCurrentUserLogin() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();

        if (authentication == null) {
            log.debug("No hay autenticación en el SecurityContext");
            return Optional.empty();
        }

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String username = jwt.getClaimAsString("preferred_username");

            if (username != null) {
                log.debug("Usuario autenticado: {}", username);
                return Optional.of(username);
            }
        }

        // Fallback al nombre del principal
        String username = authentication.getName();
        log.debug("Username desde principal: {}", username);
        return Optional.ofNullable(username);
    }

    /**
     * Obtiene el Client ID del JWT actual.
     * Extrae el claim "azp" (authorized party) que representa el cliente OAuth2.
     *
     * @return Optional con el client ID si está presente
     */
    public static Optional<String> getCurrentUserClientId() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();

        if (authentication == null) {
            log.debug("No hay autenticación en el SecurityContext");
            return Optional.empty();
        }

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String clientId = jwt.getClaimAsString("azp");

            if (clientId != null) {
                log.debug("Client ID: {}", clientId);
                return Optional.of(clientId);
            }

            // Fallback al claim "client_id"
            clientId = jwt.getClaimAsString("client_id");
            if (clientId != null) {
                log.debug("Client ID desde claim alternativo: {}", clientId);
                return Optional.of(clientId);
            }
        }

        log.debug("No se encontró client ID en el JWT");
        return Optional.empty();
    }

    /**
     * Obtiene los roles del usuario autenticado actual.
     * Extrae los roles de realm_access.roles del JWT.
     *
     * @return Lista de roles (sin el prefijo "ROLE_")
     */
    public static List<String> getCurrentUserRoles() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();

        if (authentication == null) {
            log.debug("No hay autenticación en el SecurityContext");
            return List.of();
        }

        // Opción 1: Desde GrantedAuthorities
        List<String> rolesFromAuthorities = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
            .collect(Collectors.toList());

        if (!rolesFromAuthorities.isEmpty()) {
            log.debug("Roles del usuario: {}", rolesFromAuthorities);
            return rolesFromAuthorities;
        }

        // Opción 2: Directamente del JWT
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");

            if (realmAccess != null && realmAccess.containsKey("roles")) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                log.debug("Roles desde JWT realm_access: {}", roles);
                return roles != null ? roles : List.of();
            }
        }

        log.debug("No se encontraron roles para el usuario");
        return List.of();
    }

    /**
     * Verifica si el usuario actual tiene un rol específico.
     *
     * @param role Rol a verificar (sin prefijo "ROLE_")
     * @return true si el usuario tiene el rol
     */
    public static boolean hasRole(String role) {
        return getCurrentUserRoles().contains(role);
    }

    /**
     * Verifica si el usuario actual tiene alguno de los roles especificados.
     *
     * @param roles Roles a verificar
     * @return true si el usuario tiene al menos uno de los roles
     */
    public static boolean hasAnyRole(String... roles) {
        List<String> userRoles = getCurrentUserRoles();
        for (String role : roles) {
            if (userRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Obtiene el JWT completo del usuario autenticado.
     *
     * @return Optional con el JWT si está presente
     */
    public static Optional<Jwt> getCurrentJwt() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.of(jwtAuth.getToken());
        }

        return Optional.empty();
    }

    /**
     * Obtiene el valor de un claim específico del JWT.
     *
     * @param claimName Nombre del claim
     * @return Optional con el valor del claim
     */
    public static Optional<String> getJwtClaim(String claimName) {
        return getCurrentJwt()
            .map(jwt -> jwt.getClaimAsString(claimName));
    }

    /**
     * Obtiene la dirección IP del request actual (si está disponible).
     * Útil para auditoría.
     *
     * @return Optional con la IP si está disponible en los detalles de autenticación
     */
    public static Optional<String> getCurrentUserIpAddress() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();

        if (authentication != null && authentication.getDetails() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) authentication.getDetails();
            String remoteAddress = (String) details.get("remoteAddress");

            if (remoteAddress != null) {
                log.debug("IP del usuario: {}", remoteAddress);
                return Optional.of(remoteAddress);
            }
        }

        return Optional.empty();
    }
}
