package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.Size;

public record UpdateRoleRequest(
    @Size(max = 255)
    String description
) {}
