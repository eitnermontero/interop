package bo.com.sintesis.hub.auth.web.rest;

import bo.com.sintesis.hub.auth.service.RoleService;
import bo.com.sintesis.hub.auth.service.dto.CreateRoleRequest;
import bo.com.sintesis.hub.auth.service.dto.MenuCodesRequest;
import bo.com.sintesis.hub.auth.service.dto.RoleDto;
import bo.com.sintesis.hub.auth.service.dto.UpdateRoleRequest;
import bo.com.sintesis.hub.audit.Auditable;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;

@Tag(name = "Roles", description = "Gestión de roles RBAC")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("@permissionService.hasAction('ROLES', 'READ')")
    public List<RoleDto> list() {
        return roleService.list();
    }

    @GetMapping("/{name}")
    @PreAuthorize("@permissionService.hasAction('ROLES', 'READ')")
    public RoleDto getByName(@PathVariable String name) {
        return roleService.getByName(name);
    }

    @PostMapping
    @Auditable(module = "ROLES", option = "CREAR_ROL", event = "CREATE")
    @PreAuthorize("@permissionService.hasAction('ROLES', 'CREATE')")
    public ResponseEntity<RoleDto> create(@Valid @RequestBody CreateRoleRequest req) {
        RoleDto created = roleService.create(req);
        return ResponseEntity
            .created(URI.create("/admin/roles/" + created.name()))
            .body(created);
    }

    @PutMapping("/{name}")
    @Auditable(module = "ROLES", option = "EDITAR_ROL", event = "UPDATE")
    @PreAuthorize("@permissionService.hasAction('ROLES', 'UPDATE')")
    public RoleDto update(@PathVariable String name,
                          @Valid @RequestBody UpdateRoleRequest req) {
        return roleService.update(name, req);
    }

    @DeleteMapping("/{name}")
    @Auditable(module = "ROLES", option = "ELIMINAR_ROL", event = "DELETE")
    @PreAuthorize("@permissionService.hasAction('ROLES', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        roleService.delete(name);
    }

    @GetMapping("/{name}/menus")
    @PreAuthorize("isAuthenticated()")
    public List<String> getMenus(@PathVariable String name) {
        return roleService.getMenus(name);
    }

    @PutMapping("/{name}/menus")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMenus(@PathVariable String name, @RequestBody MenuCodesRequest req) {
        roleService.updateMenus(name, req.menuCodes());
    }
}
