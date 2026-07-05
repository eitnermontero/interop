package bo.com.sintesis.hub.auth.web.rest.errors;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.stream.Collectors;

@Getter
public enum ErrorCode {

    // ── Authentication ──────────────────────────────────────────────────────────
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),

    // ── Input validation ────────────────────────────────────────────────────────
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "bad-request"),

    // ── Users ───────────────────────────────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user-not-found"),
    USER_CONFLICT(HttpStatus.CONFLICT, "user-conflict"),

    // ── Roles ───────────────────────────────────────────────────────────────────
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "role-not-found"),
    ROLE_CONFLICT(HttpStatus.CONFLICT, "role-conflict"),

    // ── Menus ───────────────────────────────────────────────────────────────────
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "menu-not-found"),
    MENU_CONFLICT(HttpStatus.CONFLICT, "menu-conflict"),
    MENU_HAS_CHILDREN(HttpStatus.CONFLICT, "menu-has-children"),
    MENU_IN_USE(HttpStatus.CONFLICT, "menu-in-use"),

    // ── Actions ─────────────────────────────────────────────────────────────────
    ACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "action-not-found"),
    ACTION_CONFLICT(HttpStatus.CONFLICT, "action-conflict"),
    ACTION_IN_USE(HttpStatus.CONFLICT, "action-in-use"),

    // ── Audit ──────────────────────────────────────────────────────────────────
    AUDIT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "audit-log-not-found"),
    AUDIT_INGEST_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "audit-ingest-unauthorized"),

    // ── Keycloak upstream ───────────────────────────────────────────────────────
    KEYCLOAK_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "keycloak-upstream-error");

    private final HttpStatus status;
    private final String typeSlug;
    private final String title;

    ErrorCode(HttpStatus status, String typeSlug) {
        this.status = status;
        this.typeSlug = typeSlug;
        this.title = slugToTitle(typeSlug);
    }

    private static String slugToTitle(String slug) {
        return Arrays.stream(slug.split("-"))
            .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .collect(Collectors.joining(" "));
    }
}
