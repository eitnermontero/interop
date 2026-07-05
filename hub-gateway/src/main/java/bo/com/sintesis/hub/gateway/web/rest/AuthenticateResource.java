package bo.com.sintesis.hub.gateway.web.rest;

import bo.com.sintesis.hub.gateway.service.dto.UserInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;

@RestController
@RequestMapping("/api")
@Tag(name = "Authentication", description = "User authentication")
public class AuthenticateResource {

    @PostMapping("/logout")
    @Operation(summary = "Logout current user and invalidate session")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return exchange.getSession()
            .flatMap(WebSession::invalidate)
            .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    @GetMapping("/authenticate")
    @Operation(summary = "Check authentication and return current user info")
    public Mono<ResponseEntity<UserInfo>> authenticate() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .flatMap(auth -> {
                if (auth instanceof OAuth2AuthenticationToken token &&
                    token.getPrincipal() instanceof OidcUser oidcUser) {
                    return Mono.just(ResponseEntity.ok(fromOidcUser(oidcUser, token)));
                }
                if (auth instanceof JwtAuthenticationToken jwtToken) {
                    return Mono.just(ResponseEntity.ok(fromJwt(jwtToken)));
                }
                return Mono.just(ResponseEntity.ok().<UserInfo>build());
            })
            .defaultIfEmpty(ResponseEntity.ok().build());
    }

    private UserInfo fromOidcUser(OidcUser user, OAuth2AuthenticationToken token) {
        Set<String> authorities = token.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

        return new UserInfo(
            user.getPreferredUsername() != null ? user.getPreferredUsername() : user.getName(),
            user.getGivenName(),
            user.getFamilyName(),
            user.getEmail(),
            user.getPicture(),
            user.getLocale(),
            authorities
        );
    }

    private UserInfo fromJwt(JwtAuthenticationToken token) {
        Jwt jwt = token.getToken();
        Set<String> authorities = token.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

        return new UserInfo(
            jwt.getClaimAsString("preferred_username") != null
                ? jwt.getClaimAsString("preferred_username")
                : jwt.getSubject(),
            jwt.getClaimAsString("given_name"),
            jwt.getClaimAsString("family_name"),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsString("picture"),
            jwt.getClaimAsString("locale"),
            authorities
        );
    }
}
