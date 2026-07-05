package bo.com.sintesis.hub.auth.service.dto;

public record RoleDto(
    String id,
    String name,
    String description,
    Boolean composite
) {}
