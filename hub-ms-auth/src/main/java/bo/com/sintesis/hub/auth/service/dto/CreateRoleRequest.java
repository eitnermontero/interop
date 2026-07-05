package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(
    @NotBlank
    @Size(min = 2, max = 100)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
             message = "Role name must be UPPER_SNAKE_CASE and start with a letter")
    String name,

    @Size(max = 255)
    String description
) {}
