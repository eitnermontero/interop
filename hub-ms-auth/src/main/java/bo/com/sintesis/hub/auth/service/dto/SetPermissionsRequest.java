package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SetPermissionsRequest(
    @NotNull
    @Valid
    List<MenuPermissionInput> permissions
) {
    public record MenuPermissionInput(
        @NotBlank String menuCode,
        @NotNull List<@NotBlank String> actions
    ) {}
}
