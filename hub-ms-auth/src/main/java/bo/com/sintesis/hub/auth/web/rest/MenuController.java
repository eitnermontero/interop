package bo.com.sintesis.hub.auth.web.rest;

import bo.com.sintesis.hub.auth.service.MenuService;
import bo.com.sintesis.hub.auth.service.dto.CreateMenuRequest;
import bo.com.sintesis.hub.auth.service.dto.MenuDto;
import bo.com.sintesis.hub.auth.service.dto.ReorderMenuRequest;
import bo.com.sintesis.hub.auth.service.dto.UpdateMenuRequest;
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

@Tag(name = "Menús", description = "Árbol de navegación y permisos por rol")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/admin/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    @PreAuthorize("@permissionService.hasAction('MENUS', 'READ')")
    public List<MenuDto> tree() {
        return menuService.tree();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permissionService.hasAction('MENUS', 'READ')")
    public MenuDto getById(@PathVariable Long id) {
        return menuService.getById(id);
    }

    @PostMapping
    @Auditable(module = "MENUS", option = "CREAR_MENU", event = "CREATE")
    @PreAuthorize("@permissionService.hasAction('MENUS', 'CREATE')")
    public ResponseEntity<MenuDto> create(@Valid @RequestBody CreateMenuRequest req) {
        MenuDto created = menuService.create(req);
        return ResponseEntity
            .created(URI.create("/admin/menus/" + created.id()))
            .body(created);
    }

    @PutMapping("/{id}")
    @Auditable(module = "MENUS", option = "EDITAR_MENU", event = "UPDATE")
    @PreAuthorize("@permissionService.hasAction('MENUS', 'UPDATE')")
    public MenuDto update(@PathVariable Long id,
                          @Valid @RequestBody UpdateMenuRequest req) {
        return menuService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @Auditable(module = "MENUS", option = "ELIMINAR_MENU", event = "DELETE")
    @PreAuthorize("@permissionService.hasAction('MENUS', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        menuService.delete(id);
    }

    @PutMapping("/reorder")
    @PreAuthorize("@permissionService.hasAction('MENUS', 'UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@Valid @RequestBody ReorderMenuRequest req) {
        menuService.reorder(req);
    }
}
