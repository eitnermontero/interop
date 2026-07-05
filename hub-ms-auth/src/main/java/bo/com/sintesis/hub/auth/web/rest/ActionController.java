package bo.com.sintesis.hub.auth.web.rest;

import bo.com.sintesis.hub.auth.service.ActionService;
import bo.com.sintesis.hub.auth.service.dto.ActionDto;
import bo.com.sintesis.hub.auth.service.dto.CreateActionRequest;
import bo.com.sintesis.hub.auth.service.dto.UpdateActionRequest;
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

@Tag(name = "Acciones", description = "Permisos granulares por módulo")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/admin/actions")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actionService;

    @GetMapping
    @PreAuthorize("@permissionService.hasAction('ACCIONES', 'READ')")
    public List<ActionDto> list() {
        return actionService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permissionService.hasAction('ACCIONES', 'READ')")
    public ActionDto getById(@PathVariable Long id) {
        return actionService.getById(id);
    }

    @PostMapping
    @Auditable(module = "ACCIONES", option = "CREAR_ACCION", event = "CREATE")
    @PreAuthorize("@permissionService.hasAction('ACCIONES', 'CREATE')")
    public ResponseEntity<ActionDto> create(@Valid @RequestBody CreateActionRequest req) {
        ActionDto created = actionService.create(req);
        return ResponseEntity
            .created(URI.create("/admin/actions/" + created.id()))
            .body(created);
    }

    @PutMapping("/{id}")
    @Auditable(module = "ACCIONES", option = "EDITAR_ACCION", event = "UPDATE")
    @PreAuthorize("@permissionService.hasAction('ACCIONES', 'UPDATE')")
    public ActionDto update(@PathVariable Long id,
                            @Valid @RequestBody UpdateActionRequest req) {
        return actionService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @Auditable(module = "ACCIONES", option = "ELIMINAR_ACCION", event = "DELETE")
    @PreAuthorize("@permissionService.hasAction('ACCIONES', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        actionService.delete(id);
    }
}
