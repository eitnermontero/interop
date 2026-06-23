package bo.com.sintesis.mdqr.gateway.web.rest;

import bo.com.sintesis.mdqr.gateway.service.AuthService;
import bo.com.sintesis.mdqr.gateway.service.dto.TokenResponse;
import bo.com.sintesis.mdqr.gateway.web.rest.request.LoginRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication")
public class AuthResource {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Exchange username/password for a JWT token")
    public Mono<ResponseEntity<TokenResponse>> login(@Valid @RequestBody LoginRequest loginRequest) {
        log.debug("REST request to login, username: {}", loginRequest.username());
        return authService.loginUser(loginRequest.username(), loginRequest.password())
            .map(ResponseEntity::ok);
    }
}
