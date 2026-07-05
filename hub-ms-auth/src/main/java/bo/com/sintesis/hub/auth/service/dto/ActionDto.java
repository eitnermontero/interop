package bo.com.sintesis.hub.auth.service.dto;

public record ActionDto(
    Long id,
    String code,
    String name,
    String description
) {}
