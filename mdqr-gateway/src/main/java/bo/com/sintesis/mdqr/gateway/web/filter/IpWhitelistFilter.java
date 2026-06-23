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

import java.net.InetSocketAddress;

/**
 * GlobalFilter that enforces per-partner IP whitelists.
 *
 * Redis key: whitelist:ip:{partnerId}  →  Set<String> of allowed CIDRs/IPs
 * Empty or absent set = no restriction (allow all).
 *
 * Runs after Spring Security (order 1) so SecurityContext is already populated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpWhitelistFilter implements GlobalFilter, Ordered {

    private static final String AUTH_PATH        = "/api/v1/auth/token";
    private static final String REDIS_KEY_PREFIX = "whitelist:ip:";

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.endsWith(AUTH_PATH)) {
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

                String clientIp = extractClientIp(exchange);
                String redisKey = REDIS_KEY_PREFIX + partnerId;

                return redisTemplate.opsForSet().size(redisKey)
                    .flatMap(size -> {
                        if (size == 0) {
                            return chain.filter(exchange);
                        }
                        return redisTemplate.opsForSet().isMember(redisKey, clientIp)
                            .flatMap(allowed -> {
                                if (Boolean.TRUE.equals(allowed)) {
                                    return chain.filter(exchange);
                                }
                                log.warn("IP {} blocked for partner={}", clientIp, partnerId);
                                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                return exchange.getResponse().setComplete();
                            });
                    })
                    .onErrorResume(e -> {
                        log.warn("Redis unavailable in IpWhitelistFilter, allowing request for partner={}: {}", partnerId, e.getMessage());
                        return chain.filter(exchange);
                    });
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    private String extractClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
