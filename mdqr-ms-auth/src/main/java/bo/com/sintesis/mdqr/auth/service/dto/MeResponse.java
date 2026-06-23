package bo.com.sintesis.mdqr.auth.service.dto;

import java.util.List;

public record MeResponse(
    String id,
    String username,
    String email,
    String firstName,
    String lastName,
    String fullName,
    List<String> roles
) {}
