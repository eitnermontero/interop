package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
    @NotNull Boolean enabled
) {}
