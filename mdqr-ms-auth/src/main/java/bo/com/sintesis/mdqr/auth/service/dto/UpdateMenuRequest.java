package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.Size;

public record UpdateMenuRequest(
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
