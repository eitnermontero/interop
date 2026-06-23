package bo.com.sintesis.mdqr.gateway.web.filter;

import bo.com.sintesis.mdqr.gateway.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * GlobalFilter that enforces per-partner rate limits using a Redis sliding window.
 *
 * Algorithm (sorted set sliding window):
 *   1. Remove members with score < (now - 60s)   — expire old requests
 *   2. Add current request with score = now       — record request
 *   3. Set key TTL = 2 min                        — auto-cleanup
 *   4. Count remaining members                    — current request count
 *   5. If count > rateLimit → 429
 *
 * Redis key: ratelimit:{partnerId}   →  ZSet<String, Double (timestamp ms)>
 * Per-partner override: ratelimit:config:{partnerId}  →  String (integer limit)
 *
 * Runs after whitelist filters (order 3).
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final String AUTH_PATH         = "/api/v1/auth/token";
    private static final String COUNTER_PREFIX    = "ratelimit:";
    private static final String CONFIG_PREFIX     = "ratelimit:config:";
    private static final long   WINDOW_MS         = 60_000L;
    private static final Duration TTL             = Duration.ofMinutes(2);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final long defaultRequestsPerMinute;

    public RateLimitFilter(
            ReactiveStringRedisTemplate redisTemplate,
            ApplicationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.defaultRequestsPerMinute = properties.gateway().rateLimit().defaultRequestsPerMinute();
    }

    @Override
    public int getOrder() {
        return 3;
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

                return resolveRateLimit(partnerId)
                    .flatMap(rateLimit -> applySlidingWindow(exchange, chain, partnerId, rateLimit))
                    .onErrorResume(e -> {
                        log.warn("Redis unavailable in RateLimitFilter, allowing request for partner={}: {}", partnerId, e.getMessage());
                        return chain.filter(exchange);
                    });
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Long> resolveRateLimit(String partnerId) {
        return redisTemplate.opsForValue().get(CONFIG_PREFIX + partnerId)
            .map(val -> {
                try {
                    return Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return defaultRequestsPerMinute;
                }
            })
            .defaultIfEmpty(defaultRequestsPerMinute);
    }

    private Mono<Void> applySlidingWindow(ServerWebExchange exchange, GatewayFilterChain chain,
                                          String partnerId, long rateLimit) {
        String key = COUNTER_PREFIX + partnerId;
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;
        String requestId = UUID.randomUUID().toString();

        return redisTemplate.opsForZSet().removeRangeByScore(key, Range.open(Double.NEGATIVE_INFINITY, (double) windowStart))
            .then(redisTemplate.opsForZSet().add(key, requestId, now))
            .then(redisTemplate.expire(key, TTL))
            .then(redisTemplate.opsForZSet().size(key))
            .flatMap(count -> {
                if (count > rateLimit) {
                    log.warn("Rate limit exceeded for partner={}, count={}, limit={}", partnerId, count, rateLimit);
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(rateLimit));
                    exchange.getResponse().getHeaders().add("Retry-After", "60");
                    return exchange.getResponse().setComplete();
                }
                return chain.filter(exchange);
            });
    }
}
