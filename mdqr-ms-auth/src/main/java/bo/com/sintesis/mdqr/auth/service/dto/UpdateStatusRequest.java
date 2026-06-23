package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
    @NotNull Boolean enabled
) {}
