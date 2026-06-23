package bo.com.sintesis.mdqr.gateway.web.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polea el discovery cada 15s y logea UNA sola linea cuando un service aparece
 * o desaparece — evita spam de healthcheck failures cada poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceAvailabilityLogger {

    private final ReactiveDiscoveryClient discoveryClient;
    private final Set<String> lastAvailable = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized = false;

    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void check() {
        discoveryClient.getServices()
            .flatMap(name -> discoveryClient.getInstances(name)
                .count()
                .map(n -> Map.entry(name, n > 0)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
            .doOnError(e -> { /* consul down: ignorar — el LB cache cubre */ })
            .onErrorResume(e -> Flux.<Map.Entry<String, Boolean>>empty().collectMap(Map.Entry::getKey, Map.Entry::getValue))
            .subscribe(this::reconcile);
    }

    private void reconcile(Map<String, Boolean> status) {
        if (status.isEmpty()) {
            return;
        }

        Set<String> nowAvailable = new HashSet<>();
        status.forEach((name, has) -> { if (Boolean.TRUE.equals(has)) nowAvailable.add(name); });

        if (!initialized) {
            lastAvailable.addAll(nowAvailable);
            initialized = true;
            return;
        }

        Set<String> appeared = new HashSet<>(nowAvailable);
        appeared.removeAll(lastAvailable);
        Set<String> disappeared = new HashSet<>(lastAvailable);
        disappeared.removeAll(nowAvailable);

        appeared.forEach(svc -> log.info("Upstream AVAILABLE: {}", svc));
        disappeared.forEach(svc -> log.warn("Upstream UNAVAILABLE: {}", svc));

        if (!appeared.isEmpty() || !disappeared.isEmpty()) {
            lastAvailable.clear();
            lastAvailable.addAll(nowAvailable);
        }
    }
}
