package bo.com.sintesis.hub.gateway.web.rest.errors;

import bo.com.sintesis.hub.gateway.web.rest.ApiError;
import bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Entry point de autenticación del gateway (ADR-0005 §6.1.b).
 *
 * <p>Reemplaza al anterior {@code ProblemDetailAuthEntryPoint}: en lugar de emitir
 * {@code application/problem+json} (RFC 7807), emite siempre {@link bo.com.sintesis.hub.gateway.web.rest.ApiResponse}
 * en {@code application/json}, garantizando que el partner reciba un único formato
 * de respuesta en todos los caminos.
 *
 * <p>Lógica de selección del {@code error.code}:
 * <ul>
 *   <li>Si la excepción es un {@link OAuth2AuthenticationException} con
 *       {@link BearerTokenError} cuya descripción o code indica expiración
 *       ({@code "expired"} en la descripción, o {@link BearerTokenErrors#INVALID_TOKEN}
 *       con mención a expiración) → {@code TOKEN_EXPIRED} (401).</li>
 *   <li>En cualquier otro caso → {@code AUTHENTICATION_REQUIRED} (401).</li>
 * </ul>
 *
 * <p>El {@code correlation_id} se obtiene del atributo del exchange puesto por
 * {@link bo.com.sintesis.hub.gateway.web.filter.CorrelationIdFilter}. Si no está
 * disponible (defensa en profundidad), {@link ApiResponseWriter} genera uno nuevo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiResponseAuthEntryPoint implements ServerAuthenticationEntryPoint {

    private static final String CODE_EXPIRED             = "TOKEN_EXPIRED";
    private static final String CODE_AUTH_REQUIRED       = "AUTHENTICATION_REQUIRED";
    private static final String MSG_EXPIRED              = "El token de acceso ha expirado";
    private static final String MSG_AUTH_REQUIRED        = "Autenticación requerida";
    private static final String DETAIL_EXPIRED           =
            "El access token presentado expiró. Solicite uno nuevo en POST /oauth2/token.";
    private static final String DETAIL_AUTH_REQUIRED     =
            "No se presentó un token de acceso válido. "
            + "Obtenga un token en POST /oauth2/token y preséntelo como Bearer.";

    private final ApiResponseWriter writer;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        boolean esTokenExpirado = detectarTokenExpirado(ex);

        String code   = esTokenExpirado ? CODE_EXPIRED     : CODE_AUTH_REQUIRED;
        String msg    = esTokenExpirado ? MSG_EXPIRED       : MSG_AUTH_REQUIRED;
        String detail = esTokenExpirado ? DETAIL_EXPIRED    : DETAIL_AUTH_REQUIRED;

        log.debug("Rechazo de autenticación — code={} causa={}",
                code, ex.getMessage());

        ApiError apiError = new ApiError(code, detail, null);
        return writer.writeError(exchange, HttpStatus.UNAUTHORIZED, apiError, msg);
    }

    /**
     * Detecta si la excepción indica que el token está expirado inspeccionando
     * la descripción del {@link BearerTokenError} o el mensaje de la excepción.
     *
     * <p>Spring Security expone el motivo de expiración a través del error
     * {@code invalid_token} con una descripción que contiene "expired" /
     * "Jwt expired" cuando el JWT falla la validación de timestamp.
     */
    private boolean detectarTokenExpirado(AuthenticationException ex) {
        if (ex instanceof OAuth2AuthenticationException oauthEx) {
            var error = oauthEx.getError();
            if (error instanceof BearerTokenError bte) {
                String description = bte.getDescription();
                if (description != null && containsExpired(description)) {
                    return true;
                }
            }
            // Fallback: inspeccionar el mensaje de la excepción
            String msg = ex.getMessage();
            if (msg != null && containsExpired(msg)) {
                return true;
            }
        }
        // Para otras AuthenticationException, no asumimos expiración
        String msg = ex.getMessage();
        return msg != null && containsExpired(msg);
    }

    private static boolean containsExpired(String text) {
        String lower = text.toLowerCase();
        return lower.contains("expired") || lower.contains("expirado");
    }
}
