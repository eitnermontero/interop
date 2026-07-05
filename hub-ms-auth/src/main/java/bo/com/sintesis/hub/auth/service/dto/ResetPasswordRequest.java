package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank @Size(min = 6, max = 100) String password,
    Boolean temporary
) {}
