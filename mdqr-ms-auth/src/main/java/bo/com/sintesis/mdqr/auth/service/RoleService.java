package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.repository.RoleMenuActionRepository;
import bo.com.sintesis.mdqr.auth.service.dto.CreateRoleRequest;
import bo.com.sintesis.mdqr.auth.service.dto.RoleDto;
import bo.com.sintesis.mdqr.auth.service.dto.UpdateRoleRequest;
import bo.com.sintesis.mdqr.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.mdqr.auth.web.rest.errors.ErrorCode;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RealmResource realm;
    private final RoleMenuActionRepository roleMenuActionRepository;

    public List<RoleDto> list() {
        return realm.roles().list().stream()
            .map(this::toDto)
            .sorted(Comparator.comparing(RoleDto::name))
            .toList();
    }

    public RoleDto getByName(String name) {
        return toDto(getRoleResource(name).toRepresentation());
    }

    public RoleDto create(CreateRoleRequest req) {
        if (existsByName(req.name())) {
            throw new AdminApiException(ErrorCode.ROLE_CONFLICT,
                "Role already exists: " + req.name());
        }
        RoleRepresentation rep = new RoleRepresentation();
        rep.setName(req.name());
        rep.setDescription(req.description());
        rep.setComposite(false);
        realm.roles().create(rep);
        return getByName(req.name());
    }

    public RoleDto update(String name, UpdateRoleRequest req) {
        var resource = getRoleResource(name);
        var rep = resource.toRepresentation();
        if (req.description() != null) {
            rep.setDescription(req.description());
        }
        resource.update(rep);
        return toDto(resource.toRepresentation());
    }

    /**
     * Deleting a role in Keycloak also removes any local permission grants
     * tied to that role name. Wrapping in a transaction so local cleanup
     * happens atomically with Keycloak removal.
     */
    @Transactional
    public void delete(String name) {
        var resource = getRoleResource(name);
        resource.remove();
        int removed = roleMenuActionRepository.deleteByRoleName(name);
        if (removed > 0) {
            log.info("Deleted {} local permission grants for role {}", removed, name);
        }
    }

    public List<String> getMenus(String roleName) {
        return roleMenuActionRepository.findMenuCodesByRoleName(roleName);
    }

    @Transactional
    public void updateMenus(String roleName, List<String> menuCodes) {
        roleMenuActionRepository.deleteByRoleName(roleName);
        if (menuCodes != null && !menuCodes.isEmpty()) {
            for (String menuCode : menuCodes) {
                roleMenuActionRepository.createMenuAssignment(roleName, menuCode);
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private RoleResource getRoleResource(String name) {
        try {
            var resource = realm.roles().get(name);
            resource.toRepresentation();
            return resource;
        } catch (NotFoundException ex) {
            throw new AdminApiException(ErrorCode.ROLE_NOT_FOUND, "Role not found: " + name);
        }
    }

    private boolean existsByName(String name) {
        try {
            realm.roles().get(name).toRepresentation();
            return true;
        } catch (NotFoundException ex) {
            return false;
        }
    }

    private RoleDto toDto(RoleRepresentation r) {
        return new RoleDto(
            r.getId(),
            r.getName(),
            r.getDescription(),
            r.isComposite()
        );
    }
}
