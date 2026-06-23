package bo.com.sintesis.mdqr.auth.web.rest;

import bo.com.sintesis.mdqr.auth.service.AuthService;
import bo.com.sintesis.mdqr.auth.service.dto.LoginRequest;
import bo.com.sintesis.mdqr.auth.service.dto.LogoutRequest;
import bo.com.sintesis.mdqr.auth.service.dto.MeResponse;
import bo.com.sintesis.mdqr.auth.service.dto.PermissionsTreeResponse;
import bo.com.sintesis.mdqr.auth.service.dto.RefreshTokenRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;

@Tag(name = "Autenticación", description = "Login, refresh, logout y perfil del usuario")
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return authService.refresh(req);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(jwt);
    }

    @GetMapping("/me/permissions")
    public PermissionsTreeResponse mePermissions(@AuthenticationPrincipal Jwt jwt) {
        return authService.mePermissions(jwt);
    }
}
