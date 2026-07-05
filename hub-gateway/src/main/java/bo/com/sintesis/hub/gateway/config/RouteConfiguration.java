package bo.com.sintesis.hub.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Las rutas de microservicios se crean automaticamente via
 * spring.cloud.gateway.discovery.locator (ver application.yml). Cada service
 * registrado en Consul aparece como /services/<serviceId>/**. Aca solo dejamos
 * rewrites ad-hoc (ej. swagger).
 */
@Configuration
public class RouteConfiguration {

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder, ApplicationProperties properties) {
        String cartService = "lb://" + properties.gateway().cartServiceName();
        return builder.routes()
            // Swagger API docs — proxy a cart-service
            .route("cart-service-api-docs", r -> r
                .path("/v3/api-docs/cart-service")
                .filters(f -> f.rewritePath("/v3/api-docs/cart-service", "/v3/api-docs"))
                .uri(cartService)
            )
            .build();
    }
}
