package bo.com.sintesis.mdqr.base.hub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
 * Configuración WebMVC para el pipeline de auditoría del hub.
 *
 * <p>Registra:
 * <ol>
 *   <li>Un filtro de servlet ({@link ContentCachingFilter}) que envuelve el request
 *       y el response en {@link ContentCachingRequestWrapper}/
 *       {@link ContentCachingResponseWrapper} solo para las rutas de QR decode.
 *       Esto permite que {@link HubAuditInterceptor} lea el body sin consumir el
 *       stream original de servlet.</li>
 *   <li>El interceptor {@link HubAuditInterceptor} limitado a
 *       {@code POST /api/qr/decode}.</li>
 * </ol>
 *
 * <p>El filtro corre con orden {@link Ordered#HIGHEST_PRECEDENCE + 1} para que
 * los wrappers estén disponibles antes de que Spring Security procese el request.
 * La llamada a {@code copyBodyToResponse()} en el filtro garantiza que el cuerpo
 * del response llegue al cliente aunque el interceptor lo haya leído primero.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HubWebMvcConfig implements WebMvcConfigurer {

    private final HubAuditInterceptor hubAuditInterceptor;

    // ─── Interceptor ─────────────────────────────────────────────────────────

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(hubAuditInterceptor)
                // Solo /api/qr/decode — no /api/qr/decode/file (multipart, diferente pipeline)
                .addPathPatterns("/api/qr/decode");
        log.info("HubAuditInterceptor registrado para /api/qr/decode");
    }

    // ─── Filtro de caching de bodies ─────────────────────────────────────────

    /**
     * Filtro que envuelve el request y el response en wrappers de caching de contenido.
     * Solo actúa sobre las rutas de negocio del hub para minimizar la sobrecarga
     * de copiar el body en memoria.
     */
    @Bean
    public FilterRegistrationBean<ContentCachingFilter> cachingFilter() {
        FilterRegistrationBean<ContentCachingFilter> registration =
                new FilterRegistrationBean<>(new ContentCachingFilter());
        registration.addUrlPatterns("/api/qr/decode");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("hubContentCachingFilter");
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
