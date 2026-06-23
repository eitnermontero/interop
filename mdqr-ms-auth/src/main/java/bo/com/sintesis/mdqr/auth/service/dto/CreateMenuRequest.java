package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMenuRequest(
    @NotBlank
    @Size(min = 2, max = 100)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
             message = "Menu code must be UPPER_SNAKE_CASE")
    String code,

    @NotBlank
    @Size(min = 1, max = 100)
    String name,

    @Size(max = 100)
    String icon,

    @Size(max = 255)
    String route,

    Long parentId,

    Integer orderIndex,

    Boolean isActive
) {}
