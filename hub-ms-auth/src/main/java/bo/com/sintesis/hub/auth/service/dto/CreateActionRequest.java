package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateActionRequest(
    @NotBlank
    @Size(min = 2, max = 50)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
             message = "Action code must be UPPER_SNAKE_CASE")
    String code,

    @NotBlank
    @Size(min = 1, max = 100)
    String name,

    @Size(max = 255)
    String description
) {}
