package bo.com.sintesis.mdqr.audit;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Acquires and caches a Keycloak {@code client_credentials} access token used to
 * authenticate audit ingestion against the admin service.
 *
 * Thread-safe: the cached token is read without locking; a single thread refreshes
 * it under a lock when missing or within {@link #REFRESH_MARGIN} of expiry. Worker
 * threads draining the audit buffer call {@link #token()} at send time, so a token
 * is never cached inside an event that may wait in the buffer.
 */
public final class ServiceTokenProvider {

    /** Refresh ahead of the real expiry to never present a token that expires mid-flight. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final RestClient http;
    private final String tokenUri;
    private final String basicAuth;
    private final String scope;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ServiceTokenProvider(RestClient http, String tokenUri,
                                String clientId, String clientSecret, String scope) {
        this.http = http;
        this.tokenUri = tokenUri;
        this.scope = scope;
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns a valid bearer token, refreshing under lock when missing or near expiry. */
    public String token() {
        if (isFresh()) {
            return cachedToken;
        }
        lock.lock();
        try {
            if (isFresh()) {
                return cachedToken;
            }
            refresh();
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private boolean isFresh() {
        return cachedToken != null && Instant.now().isBefore(expiresAt.minus(REFRESH_MARGIN));
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (scope != null && !scope.isBlank()) {
            form.add("scope", scope);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = http.post()
            .uri(tokenUri)
            .header("Authorization", basicAuth)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("Token endpoint returned no access_token: " + tokenUri);
        }

        cachedToken = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 60L;
        expiresAt = Instant.now().plusSeconds(expiresIn);
    }
}
