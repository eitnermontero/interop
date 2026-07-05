package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateUserRequest(
    @NotBlank @Size(max = 100) String username,
    @Email @NotBlank @Size(max = 255) String email,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    Boolean enabled,
    Boolean emailVerified,
    String password,
    Boolean temporaryPassword,
    List<String> roles,
    Map<String, List<String>> attributes
) {}
