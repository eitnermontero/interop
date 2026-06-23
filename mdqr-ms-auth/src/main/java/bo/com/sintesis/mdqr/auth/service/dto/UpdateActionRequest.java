package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.Size;

public record UpdateActionRequest(
    @Size(min = 1, max = 100)
    String name,

    @Size(max = 255)
    String description
) {}
