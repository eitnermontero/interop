package bo.com.sintesis.mdqr.auth.web.rest.errors;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ProblemWriter problemWriter;

    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<Map<String, Object>> handleAdminException(AdminApiException ex) {
        Map<String, Object> body = ex.getMessage() != null && !ex.getMessage().equals(ex.getCode().name())
            ? problemWriter.problem(ex.getCode(), ex.getMessage())
            : problemWriter.problem(ex.getCode());
        return ResponseEntity.status(ex.getCode().getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        Map<String, Object> body = problemWriter.problem(ErrorCode.VALIDATION_ERROR, detail);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(body);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakNotFound(NotFoundException ex) {
        Map<String, Object> body = problemWriter.problem(ErrorCode.USER_NOT_FOUND);
        return ResponseEntity.status(ErrorCode.USER_NOT_FOUND.getStatus()).body(body);
    }

    @ExceptionHandler(WebApplicationException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakWebException(WebApplicationException ex) {
        log.warn("Keycloak upstream error: {} {}", ex.getResponse().getStatus(), ex.getMessage());
        int upstreamStatus = ex.getResponse().getStatus();
        if (upstreamStatus == 404) {
            return ResponseEntity.status(ErrorCode.USER_NOT_FOUND.getStatus())
                .body(problemWriter.problem(ErrorCode.USER_NOT_FOUND));
        }
        if (upstreamStatus == 409) {
            return ResponseEntity.status(ErrorCode.USER_CONFLICT.getStatus())
                .body(problemWriter.problem(ErrorCode.USER_CONFLICT));
        }
        return ResponseEntity.status(ErrorCode.KEYCLOAK_UPSTREAM_ERROR.getStatus())
            .body(problemWriter.problem(ErrorCode.KEYCLOAK_UPSTREAM_ERROR, ex.getMessage()));
    }

    // @PreAuthorize / @permissionService.hasAction lanzan AuthorizationDeniedException
    // (subclase de AccessDeniedException). Es un 403 esperado, no un error 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        Map<String, Object> body = problemWriter.problem(ErrorCode.FORBIDDEN);
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        Map<String, Object> body = problemWriter.problem(ErrorCode.KEYCLOAK_UPSTREAM_ERROR, ex.getMessage());
        return ResponseEntity.status(ErrorCode.KEYCLOAK_UPSTREAM_ERROR.getStatus()).body(body);
    }
}
