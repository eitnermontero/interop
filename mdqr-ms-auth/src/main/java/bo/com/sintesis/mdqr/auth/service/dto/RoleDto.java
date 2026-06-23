package bo.com.sintesis.mdqr.auth.service.dto;

public record RoleDto(
    String id,
    String name,
    String description,
    Boolean composite
) {}
