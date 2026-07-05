package bo.com.sintesis.hub.auth.web.rest;

import bo.com.sintesis.hub.auth.service.UserService;
import bo.com.sintesis.hub.auth.service.dto.CreateUserRequest;
import bo.com.sintesis.hub.auth.service.dto.PageResponse;
import bo.com.sintesis.hub.auth.service.dto.ResetPasswordRequest;
import bo.com.sintesis.hub.auth.service.dto.UpdateRolesRequest;
import bo.com.sintesis.hub.auth.service.dto.UpdateStatusRequest;
import bo.com.sintesis.hub.auth.service.dto.UpdateUserRequest;
import bo.com.sintesis.hub.auth.service.dto.UserDto;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;

@Tag(name = "Usuarios", description = "Gestión de usuarios del sistema en Keycloak")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'READ')")
    public PageResponse<UserDto> list(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Boolean enabled,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return userService.list(search, enabled, page, size);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'READ')")
    public UserDto getById(@PathVariable String userId) {
        return userService.getById(userId);
    }

    @PostMapping
    @Auditable(module = "USUARIOS", option = "CREAR_USUARIO", event = "CREATE", includeRequestBody = true, excludeParams = {"req"})
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'CREATE')")
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest req) {
        UserDto created = userService.create(req);
        return ResponseEntity
            .created(URI.create("/admin/users/" + created.id()))
            .body(created);
    }

    @PutMapping("/{userId}")
    @Auditable(module = "USUARIOS", option = "EDITAR_USUARIO", event = "UPDATE")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'UPDATE')")
    public UserDto update(@PathVariable String userId,
                          @Valid @RequestBody UpdateUserRequest req) {
        return userService.update(userId, req);
    }

    @DeleteMapping("/{userId}")
    @Auditable(module = "USUARIOS", option = "ELIMINAR_USUARIO", event = "DELETE")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String userId) {
        userService.delete(userId);
    }

    @PutMapping("/{userId}/password")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String userId,
                              @Valid @RequestBody ResetPasswordRequest req) {
        userService.setPassword(userId, req.password(),
            req.temporary() != null && req.temporary());
    }

    @PutMapping("/{userId}/status")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateStatus(@PathVariable String userId,
                             @Valid @RequestBody UpdateStatusRequest req) {
        userService.setStatus(userId, req.enabled());
    }

    @GetMapping("/{userId}/roles")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'READ')")
    public List<String> getRoles(@PathVariable String userId) {
        return userService.getRealmRoles(userId);
    }

    @PutMapping("/{userId}/roles")
    @Auditable(module = "USUARIOS", option = "ASIGNAR_ROLES", event = "UPDATE")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceRoles(@PathVariable String userId,
                             @Valid @RequestBody UpdateRolesRequest req) {
        userService.replaceRealmRoles(userId, req.roles());
    }

    @PostMapping("/{userId}/send-reset")
    @PreAuthorize("@permissionService.hasAction('USUARIOS', 'UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendResetEmail(@PathVariable String userId) {
        userService.sendResetPasswordEmail(userId);
    }
}
