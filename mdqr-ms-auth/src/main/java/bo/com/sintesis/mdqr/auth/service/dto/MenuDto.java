package bo.com.sintesis.mdqr.auth.service.dto;

import java.util.List;

public record MenuDto(
    Long id,
    String code,
    String name,
    String icon,
    String route,
    Long parentId,
    Integer orderIndex,
    Boolean isActive,
    List<MenuDto> children
) {}
