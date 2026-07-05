package bo.com.sintesis.hub.base.hub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Configuración WebMVC para el pipeline de auditoría del hub inbound.
 *
 * <p>Registra:
 * <ul>
 *   <li>{@link ContentCachingFilter} — envuelve request y response para que el body
 *       pueda ser leído múltiples veces por {@link HubAuditInterceptor}.</li>
 *   <li>{@link HubAuditInterceptor} — captura inicio/fin de cada petición para
 *       registrar la auditoría completa (hash request, hash response, latencia, outbox).</li>
 * </ul>
 *
 * <p>Ambos están registrados únicamente sobre {@code /api/inbound/**} para no
 * interferir con el resto de endpoints del microservicio.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HubWebMvcConfig implements WebMvcConfigurer {

    private final HubAuditInterceptor hubAuditInterceptor;

    /** Patrón de rutas cubierto por el interceptor y el filtro de caching. */
    private static final String INBOUND_PATTERN = "/api/inbound/**";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(hubAuditInterceptor)
                .addPathPatterns(INBOUND_PATTERN);
        log.info("HubAuditInterceptor registrado sobre '{}'", INBOUND_PATTERN);
    }

    /**
     * Filtro de servlet que aplica {@link ContentCachingRequestWrapper} y
     * {@link ContentCachingResponseWrapper} y garantiza que el body del response
     * se copie al stream real al terminar la cadena de filtros.
     *
     * <p>El orden por defecto es suficiente — debe ejecutarse antes que el
     * interceptor de auditoría para que los wrappers estén disponibles en
     * {@code preHandle} y {@code afterCompletion}.
     */
    @Bean
    public FilterRegistrationBean<ContentCachingFilter> cachingFilter() {
        FilterRegistrationBean<ContentCachingFilter> registration =
                new FilterRegistrationBean<>(new ContentCachingFilter());
        registration.addUrlPatterns("/api/inbound/*");
        registration.setName("hubContentCachingFilter");
        log.info("ContentCachingFilter registrado sobre '/api/inbound/*'");
        return registration;
    }

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
