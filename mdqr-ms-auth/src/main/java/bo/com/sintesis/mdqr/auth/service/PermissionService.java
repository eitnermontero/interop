package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.domain.Action;
import bo.com.sintesis.mdqr.auth.domain.Menu;
import bo.com.sintesis.mdqr.auth.domain.RoleMenuAction;
import bo.com.sintesis.mdqr.auth.domain.RoleMenuActionId;
import bo.com.sintesis.mdqr.auth.repository.ActionRepository;
import bo.com.sintesis.mdqr.auth.repository.MenuRepository;
import bo.com.sintesis.mdqr.auth.repository.RoleMenuActionRepository;
import bo.com.sintesis.mdqr.auth.service.dto.RolePermissionsResponse;
import bo.com.sintesis.mdqr.auth.service.dto.SetPermissionsRequest;
import bo.com.sintesis.mdqr.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.mdqr.auth.web.rest.errors.ErrorCode;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service("permissionService")
@RequiredArgsConstructor
public class PermissionService {

    private final RealmResource realm;
    private final MenuRepository menuRepository;
    private final ActionRepository actionRepository;
    private final RoleMenuActionRepository roleMenuActionRepository;

    /**
     * Returns the current permission matrix for a role.
     * Validates that the role exists in Keycloak so the UI never edits a phantom role.
     */
    @Transactional(readOnly = true)
    public RolePermissionsResponse getRolePermissions(String roleName) {
        validateRoleExists(roleName);

        List<RoleMenuAction> grants = roleMenuActionRepository.findByRoleName(roleName);

        // Group grants by menu, preserving menu order_index for a stable response.
        Map<Long, RolePermissionsResponse.MenuPermission> byMenuId = new LinkedHashMap<>();
        grants.stream()
            .sorted(Comparator
                .comparingInt((RoleMenuAction r) -> r.getMenu().getOrderIndex() == null
                    ? 0 : r.getMenu().getOrderIndex())
                .thenComparing(r -> r.getMenu().getCode()))
            .forEach(g -> {
                Menu m = g.getMenu();
                Action a = g.getAction();
                byMenuId.computeIfAbsent(m.getId(),
                    k -> new RolePermissionsResponse.MenuPermission(
                        m.getCode(), m.getName(), new ArrayList<>()))
                    .actions().add(a.getCode());
            });

        return new RolePermissionsResponse(roleName, List.copyOf(byMenuId.values()));
    }

    /**
     * Replaces the role's full permission matrix with the supplied grants.
     * Validates role + menus + actions exist before touching the database.
     */
    @Transactional
    public void setRolePermissions(String roleName, SetPermissionsRequest req) {
        validateRoleExists(roleName);

        Map<String, Menu> menusByCode = menuRepository.findAll().stream()
            .collect(Collectors.toMap(Menu::getCode, m -> m));
        Map<String, Action> actionsByCode = actionRepository.findAll().stream()
            .collect(Collectors.toMap(Action::getCode, a -> a));

        // Validate references and deduplicate (menuCode, actionCode) pairs.
        Map<String, Map<String, Boolean>> seen = new HashMap<>();
        List<RoleMenuAction> toInsert = new ArrayList<>();

        for (var perm : req.permissions()) {
            Menu menu = menusByCode.get(perm.menuCode());
            if (menu == null) {
                throw new AdminApiException(ErrorCode.MENU_NOT_FOUND,
                    "Menu code not found: " + perm.menuCode());
            }
            for (String actionCode : perm.actions()) {
                Action action = actionsByCode.get(actionCode);
                if (action == null) {
                    throw new AdminApiException(ErrorCode.ACTION_NOT_FOUND,
                        "Action code not found: " + actionCode);
                }
                boolean alreadySeen = seen
                    .computeIfAbsent(perm.menuCode(), k -> new HashMap<>())
                    .putIfAbsent(actionCode, Boolean.TRUE) != null;
                if (alreadySeen) {
                    continue;
                }

                RoleMenuAction rma = new RoleMenuAction();
                RoleMenuActionId id = new RoleMenuActionId(
                    roleName, menu.getId(), action.getId());
                rma.setId(id);
                rma.setMenu(menu);
                rma.setAction(action);
                rma.setIsGranted(Boolean.TRUE);
                toInsert.add(rma);
            }
        }

        int removed = roleMenuActionRepository.deleteByRoleName(roleName);
        // Flush deletions before inserts to avoid PK conflicts within the same tx.
        roleMenuActionRepository.flush();
        roleMenuActionRepository.saveAll(toInsert);

        log.info("Updated permissions for role {}: removed {}, added {}",
            roleName, removed, toInsert.size());
    }

    /**
     * Authorization check used by @PreAuthorize("@permissionService.hasAction(...)").
     * Reads the user's realm roles from the current JWT.
     */
    @Transactional(readOnly = true)
    public boolean hasAction(String menuCode, String actionCode) {
        List<String> roles = currentUserRoles();
        if (roles.isEmpty()) return false;
        return roleMenuActionRepository.existsGrant(roles, menuCode, actionCode);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void validateRoleExists(String roleName) {
        try {
            realm.roles().get(roleName).toRepresentation();
        } catch (NotFoundException ex) {
            throw new AdminApiException(ErrorCode.ROLE_NOT_FOUND,
                "Role not found: " + roleName);
        }
    }

    private List<String> currentUserRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Object claim = jwtAuth.getToken().getClaim("realm_access");
            if (claim instanceof Map<?, ?> realmAccess) {
                Object roles = realmAccess.get("roles");
                if (roles instanceof List<?> list) {
                    return list.stream().map(Object::toString).toList();
                }
            }
        }
        return List.of();
    }
}
