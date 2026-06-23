package bo.com.sintesis.mdqr.auth.web.rest;

import bo.com.sintesis.mdqr.auth.service.PermissionService;
import bo.com.sintesis.mdqr.auth.service.dto.RolePermissionsResponse;
import bo.com.sintesis.mdqr.auth.service.dto.SetPermissionsRequest;
import bo.com.sintesis.mdqr.audit.Auditable;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Permisos", description = "Asignación de permisos a roles")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/admin/roles/{name}/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("@permissionService.hasAction('PERMISOS', 'READ')")
    public RolePermissionsResponse get(@PathVariable("name") String roleName) {
        return permissionService.getRolePermissions(roleName);
    }

    @PutMapping
    @Auditable(module = "PERMISOS", option = "ASIGNAR_PERMISOS", event = "UPDATE")
    @PreAuthorize("@permissionService.hasAction('PERMISOS', 'UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@PathVariable("name") String roleName,
                    @Valid @RequestBody SetPermissionsRequest req) {
        permissionService.setRolePermissions(roleName, req);
    }
}
