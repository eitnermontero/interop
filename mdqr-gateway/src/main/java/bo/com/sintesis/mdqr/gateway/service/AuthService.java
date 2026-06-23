package bo.com.sintesis.mdqr.gateway.service;

import bo.com.sintesis.mdqr.gateway.config.ApplicationProperties;
import bo.com.sintesis.mdqr.gateway.service.dto.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class AuthService {

    private final WebClient keycloakWebClient;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public AuthService(ApplicationProperties properties) {
        var kc = properties.keycloak();
        this.realm = kc.realm();
        this.clientId = kc.resource();
        this.clientSecret = kc.credentials().secret();
        this.keycloakWebClient = WebClient.builder()
            .baseUrl(kc.authServerUrl())
            .build();
    }

    public Mono<TokenResponse> loginUser(String username, String password) {
        var tokenUrl = "/realms/" + realm + "/protocol/openid-connect/token";

        var formData = new LinkedMultiValueMap<String, String>();
        formData.add("grant_type", "password");
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("username", username);
        formData.add("password", password);

        return keycloakWebClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .onStatus(status -> status.is4xxClientError(), ignored ->
                Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")))
            .bodyToMono(TokenResponse.class)
            .doOnError(ex -> log.error("Keycloak login failed for user={}: {}", username, ex.getMessage()));
    }
}
