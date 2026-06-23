package bo.com.sintesis.mdqr.base.hub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Configuración WebMVC para el pipeline de auditoría del hub.
 *
 * <p><b>Estado (ADR-0004 paso 2)</b>: interceptor y filtro NEUTRALIZADOS temporalmente.
 * El endpoint de negocio aún no existe; se re-activarán contra {@code /api/caso/**}
 * cuando esté implementado. {@link HubAuditInterceptor} sigue siendo un {@code @Component}
 * y está instanciado — simplemente no está registrado en ninguna ruta.
 *
 * <p>Para re-activar: inyectar {@link HubAuditInterceptor}, sobreescribir
 * {@code addInterceptors} con el nuevo path, y registrar {@link ContentCachingFilter}
 * con el mismo path como {@code FilterRegistrationBean}.
 */
@Slf4j
@Configuration
public class HubWebMvcConfig implements WebMvcConfigurer {

    // Interceptor y filtro de caching desregistrados — ver javadoc de clase (ADR-0004 paso 2).

    /**
     * Filtro de servlet que aplica {@link ContentCachingRequestWrapper} y
     * {@link ContentCachingResponseWrapper} y garantiza que el body del response
     * se copie al stream real al terminar la cadena de filtros.
     */
    static class ContentCachingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(@SuppressWarnings("NullableProblems") HttpServletRequest request,
                                        @SuppressWarnings("NullableProblems") HttpServletResponse response,
                                        @SuppressWarnings("NullableProblems") FilterChain filterChain)
                throws ServletException, IOException {

            ContentCachingRequestWrapper  cachedRequest  = new ContentCachingRequestWrapper(request, 10 * 1024 * 1024);
            ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);

            try {
                filterChain.doFilter(cachedRequest, cachedResponse);
            } finally {
                // CRÍTICO: copiar el body cacheado al response real antes de que salga al cliente.
                // Sin esto el cliente recibe una respuesta vacía.
                cachedResponse.copyBodyToResponse();
            }
        }
    }
}
