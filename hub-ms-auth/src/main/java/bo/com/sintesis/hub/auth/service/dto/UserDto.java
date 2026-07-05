package bo.com.sintesis.hub.auth.service.dto;

import java.util.List;
import java.util.Map;

public record UserDto(
    String id,
    String username,
    String email,
    String firstName,
    String lastName,
    Boolean enabled,
    Boolean emailVerified,
    Long createdTimestamp,
    Map<String, List<String>> attributes
) {}
