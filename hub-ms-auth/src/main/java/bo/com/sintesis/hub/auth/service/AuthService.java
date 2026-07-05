package bo.com.sintesis.hub.auth.service;

import bo.com.sintesis.hub.auth.config.ApplicationProperties;
import bo.com.sintesis.hub.auth.domain.Menu;
import bo.com.sintesis.hub.auth.domain.RoleMenuAction;
import bo.com.sintesis.hub.auth.repository.MenuRepository;
import bo.com.sintesis.hub.auth.repository.RoleMenuActionRepository;
import bo.com.sintesis.hub.auth.service.dto.LoginRequest;
import bo.com.sintesis.hub.auth.service.dto.LogoutRequest;
import bo.com.sintesis.hub.auth.service.dto.MeResponse;
import bo.com.sintesis.hub.auth.service.dto.PermissionsTreeResponse;
import bo.com.sintesis.hub.auth.service.dto.PermissionsTreeResponse.MenuNode;
import bo.com.sintesis.hub.auth.service.dto.PermissionsTreeResponse.UserInfo;
import bo.com.sintesis.hub.auth.service.dto.RefreshTokenRequest;
import bo.com.sintesis.hub.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.hub.auth.web.rest.errors.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_URI  = "/protocol/openid-connect/token";
    private static final String LOGOUT_URI = "/protocol/openid-connect/logout";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RestClient keycloakTokenClient;
    private final ApplicationProperties props;
    private final MenuRepository menuRepository;
    private final RoleMenuActionRepository roleMenuActionRepository;

    // ── Token proxy (Keycloak) ──────────────────────────────────────────────

    public Map<String, Object> login(LoginRequest req) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", props.keycloak().clientId());
        if (hasSecret()) form.add("client_secret", props.keycloak().credentials().secret());
        form.add("username", req.username());
        form.add("password", req.password());
        form.add("scope", "openid");
        return postForm(TOKEN_URI, form, "login");
    }

    public Map<String, Object> refresh(RefreshTokenRequest req) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", props.keycloak().clientId());
        if (hasSecret()) form.add("client_secret", props.keycloak().credentials().secret());
        form.add("refresh_token", req.refreshToken());
        return postForm(TOKEN_URI, form, "refresh");
    }

    public void logout(LogoutRequest req) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.keycloak().clientId());
        if (hasSecret()) form.add("client_secret", props.keycloak().credentials().secret());
        form.add("refresh_token", req.refreshToken());
        try {
            keycloakTokenClient.post()
                .uri(LOGOUT_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            // Logout is idempotent: an invalid/expired token still means "logged out".
            log.info("Keycloak logout returned {}: token already invalid", ex.getStatusCode());
        }
    }

    // ── /me ─────────────────────────────────────────────────────────────────

    public MeResponse me(Jwt jwt) {
        String username  = jwt.getClaimAsString("preferred_username");
        String firstName = jwt.getClaimAsString("given_name");
        String lastName  = jwt.getClaimAsString("family_name");
        String fullName  = computeFullName(jwt, firstName, lastName, username);
        return new MeResponse(
            jwt.getSubject(),
            username,
            jwt.getClaimAsString("email"),
            firstName,
            lastName,
            fullName,
            extractRealmRoles(jwt)
        );
    }

    // ── /me/permissions: build menu tree ────────────────────────────────────

    @Transactional(readOnly = true)
    public PermissionsTreeResponse mePermissions(Jwt jwt) {
        UserInfo user = buildUserInfo(jwt);
        if (user.roles().isEmpty()) {
            return new PermissionsTreeResponse(user, List.of());
        }

        List<RoleMenuAction> grants = roleMenuActionRepository.findGrantedByRoles(user.roles());

        // Aggregate action codes per menu, deduplicated across the user's roles.
        Map<Long, Set<String>> actionsByMenuId = new HashMap<>();
        for (RoleMenuAction g : grants) {
            actionsByMenuId
                .computeIfAbsent(g.getMenu().getId(), k -> new LinkedHashSet<>())
                .add(g.getAction().getCode());
        }

        // Load every active menu so we can include parents of granted leaves.
        List<Menu> allActive = menuRepository.findAllByOrderByOrderIndexAsc().stream()
            .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
            .toList();
        Map<Long, Menu> activeById = new HashMap<>();
        for (Menu m : allActive) activeById.put(m.getId(), m);

        // Walk parents up from each granted menu so containers appear in the tree.
        Set<Long> relevantIds = new HashSet<>(actionsByMenuId.keySet());
        for (Long id : new ArrayList<>(actionsByMenuId.keySet())) {
            Menu current = activeById.get(id);
            while (current != null && current.getParent() != null) {
                Menu parent = activeById.get(current.getParent().getId());
                if (parent == null) break;
                relevantIds.add(parent.getId());
                current = parent;
            }
        }

        // Group by parent_id and sort by order_index per level.
        Map<Long, List<Menu>> byParent = new HashMap<>();
        for (Menu m : allActive) {
            if (!relevantIds.contains(m.getId())) continue;
            Long parentId = m.getParent() == null ? null : m.getParent().getId();
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(m);
        }
        for (List<Menu> level : byParent.values()) {
            level.sort(Comparator.comparingInt(
                m -> m.getOrderIndex() == null ? 0 : m.getOrderIndex()));
        }

        List<MenuNode> roots = buildNodes(null, byParent, actionsByMenuId);
        return new PermissionsTreeResponse(user, roots);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Map<String, Object> postForm(String uri, MultiValueMap<String, String> form, String op) {
        try {
            return keycloakTokenClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(MAP_TYPE);
        } catch (RestClientResponseException ex) {
            log.warn("Keycloak {} failed: {} {}", op, ex.getStatusCode(), ex.getResponseBodyAsString());
            if (ex.getStatusCode().is4xxClientError()) {
                throw new AdminApiException(ErrorCode.UNAUTHENTICATED,
                    ex.getResponseBodyAsString());
            }
            throw new AdminApiException(ErrorCode.KEYCLOAK_UPSTREAM_ERROR,
                ex.getResponseBodyAsString());
        }
    }

    private boolean hasSecret() {
        var creds = props.keycloak().credentials();
        return creds != null && creds.secret() != null && !creds.secret().isBlank();
    }

    private UserInfo buildUserInfo(Jwt jwt) {
        String username  = jwt.getClaimAsString("preferred_username");
        String firstName = jwt.getClaimAsString("given_name");
        String lastName  = jwt.getClaimAsString("family_name");
        String fullName  = computeFullName(jwt, firstName, lastName, username);
        return new UserInfo(
            jwt.getSubject(),
            username,
            jwt.getClaimAsString("email"),
            fullName,
            extractRealmRoles(jwt)
        );
    }

    private String computeFullName(Jwt jwt, String first, String last, String fallback) {
        String full = jwt.getClaimAsString("name");
        if (full != null && !full.isBlank()) return full;
        String combined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return combined.isEmpty() ? fallback : combined;
    }

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

    private List<MenuNode> buildNodes(Long parentId,
                                      Map<Long, List<Menu>> byParent,
                                      Map<Long, Set<String>> actionsByMenuId) {
        List<Menu> level = byParent.getOrDefault(parentId, List.of());
        List<MenuNode> nodes = new ArrayList<>(level.size());
        for (Menu m : level) {
            Set<String> actions = actionsByMenuId.getOrDefault(m.getId(), Set.of());
            nodes.add(new MenuNode(
                m.getCode(),
                m.getName(),
                m.getIcon(),
                m.getRoute(),
                List.copyOf(actions),
                buildNodes(m.getId(), byParent, actionsByMenuId)
            ));
        }
        return nodes;
    }
}
