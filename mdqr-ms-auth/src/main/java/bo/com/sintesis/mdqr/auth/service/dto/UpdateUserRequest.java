package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record UpdateUserRequest(
    @Email @Size(max = 255) String email,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    Boolean enabled,
    Boolean emailVerified,
    Map<String, List<String>> attributes
) {}
