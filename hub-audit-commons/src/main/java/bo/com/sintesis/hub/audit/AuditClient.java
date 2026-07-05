package bo.com.sintesis.hub.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@RequiredArgsConstructor
public class AuditClient {

    private final RestClient restClient;
    private final String endpoint;
    private final ServiceTokenProvider tokenProvider;

    /** Synchronous POST. Throws on non-2xx. Called by the publisher worker thread. */
    public void send(AuditEventDto event) {
        restClient.post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.token())
            .body(event)
            .retrieve()
            .toBodilessEntity();
    }
}
