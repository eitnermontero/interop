package bo.com.sintesis.hub.gateway.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserInfo(
    String login,
    String firstName,
    String lastName,
    String email,
    String imageUrl,
    String langKey,
    Set<String> authorities
) {}
