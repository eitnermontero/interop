package bo.com.sintesis.mdqr.gateway.web.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * GlobalFilter that enforces per-partner domain whitelists.
 * Checks the Origin header (primary) or Referer header (fallback).
 *
 * Redis key: whitelist:domain:{partnerId}  →  Set<String> of allowed hostnames
 * Empty or absent set = no restriction (allow all).
 *
 * Runs after Spring Security (order 2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainWhitelistFilter implements GlobalFilter, Ordered {

    private static final String AUTH_PATH        = "/api/v1/auth/token";
    private static final String REDIS_KEY_PREFIX = "whitelist:domain:";

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.endsWith(AUTH_PATH)) {
            return chain.filter(exchange);
        }

        String requestDomain = extractDomain(exchange);
        if (requestDomain == null) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
            .flatMap(ctx -> {
                if (!(ctx.getAuthentication().getPrincipal() instanceof Jwt jwt)) {
                    return chain.filter(exchange);
                }
                String partnerId = jwt.getClaimAsString("azp");
                if (partnerId == null) {
                    return chain.filter(exchange);
                }

                String redisKey = REDIS_KEY_PREFIX + partnerId;

                return redisTemplate.opsForSet().size(redisKey)
                    .flatMap(size -> {
                        if (size == 0) {
                            return chain.filter(exchange);
                        }
                        return redisTemplate.opsForSet().isMember(redisKey, requestDomain)
                            .flatMap(allowed -> {
                                if (Boolean.TRUE.equals(allowed)) {
                                    return chain.filter(exchange);
                                }
                                log.warn("Domain {} blocked for partner={}", requestDomain, partnerId);
                                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                return exchange.getResponse().setComplete();
                            });
                    });
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    private String extractDomain(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank()) {
            return extractHost(origin);
        }
        String referer = exchange.getRequest().getHeaders().getFirst("Referer");
        if (referer != null && !referer.isBlank()) {
            return extractHost(referer);
        }
        return null;
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
