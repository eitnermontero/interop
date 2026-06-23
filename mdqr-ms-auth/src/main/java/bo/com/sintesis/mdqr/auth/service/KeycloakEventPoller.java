package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.config.ApplicationProperties;
import bo.com.sintesis.mdqr.auth.repository.AuditLogRepository;
import bo.com.sintesis.mdqr.auth.service.dto.KeycloakEventRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.EventRepresentation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Polls the Keycloak Admin API for LOGIN/LOGOUT events and ingests them into the
 * central audit_log. Active ingestion path; the push webhook endpoint is retained
 * but no longer the primary channel.
 *
 * Dedup: cursor = MAX(event_time) WHERE service_name='keycloak' in audit_log,
 * loaded on startup. Between polls we advance the cursor to max(event.time) in the
 * batch and remember the event IDs at that timestamp to skip exact-time duplicates
 * on the next cycle.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "application.audit.keycloak-poll", name = "enabled", havingValue = "true", matchIfMissing = false)
public class KeycloakEventPoller {

    static final String SERVICE_NAME = "keycloak";
    private static final List<String> POLL_TYPES = List.of(
        "LOGIN", "LOGIN_ERROR", "LOGOUT", "LOGOUT_ERROR",
        "REFRESH_TOKEN", "REFRESH_TOKEN_ERROR"
    );
    // Keycloak Admin API /events dateFrom expects yyyy-MM-dd (date only); exact-timestamp
    // filtering is done in-memory against the cursor.
    private static final DateTimeFormatter KC_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final RealmResource realm;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationProperties props;

    // In-memory cursor: epoch millis of the last processed event
    private volatile long cursorMs;
    // Keycloak event IDs seen at cursorMs to avoid re-ingesting exact-timestamp duplicates
    private volatile Set<String> cursorIds = new HashSet<>();

    public KeycloakEventPoller(
            RealmResource realm,
            AuditLogService auditLogService,
            AuditLogRepository auditLogRepository,
            ApplicationProperties props) {
        this.realm = realm;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.props = props;
    }

    @PostConstruct
    void initCursor() {
        auditLogRepository.findMaxEventTimeByServiceName(SERVICE_NAME)
            .ifPresentOrElse(
                max -> {
                    cursorMs = max.toEpochMilli();
                    log.info("[keycloak-poll] cursor initialized from DB: {}", max);
                },
                () -> {
                    // No prior keycloak events: start from one cycle back to avoid missing recent events
                    cursorMs = Instant.now().minusSeconds(
                        pollConfig().intervalMs() / 1000 + 60).toEpochMilli();
                    log.info("[keycloak-poll] no prior keycloak events in audit_log - cursor set to {}", Instant.ofEpochMilli(cursorMs));
                }
            );
    }

    @Scheduled(fixedDelayString = "${application.audit.keycloak-poll.interval-ms:60000}")
    public void poll() {
        if (!pollConfig().enabled()) {
            return;
        }
        try {
            doPoll();
        } catch (Exception ex) {
            // Never let a poll failure propagate: next cycle will retry from the same cursor
            log.warn("[keycloak-poll] poll failed - will retry next cycle: {}", ex.getMessage(), ex);
        }
    }

    void doPoll() {
        String dateFrom = KC_DATE_FMT.format(Instant.ofEpochMilli(cursorMs));
        int max = pollConfig().maxEventsPerCycle();

        List<EventRepresentation> events = realm.getEvents(POLL_TYPES, null, null, dateFrom, null, null, 0, max);

        if (events == null || events.isEmpty()) {
            log.debug("[keycloak-poll] no new events since {}", dateFrom);
            return;
        }

        int ingested = 0;
        long newCursorMs = cursorMs;
        Set<String> newCursorIds = new HashSet<>(cursorIds);

        for (EventRepresentation ev : events) {
            long evTime = ev.getTime();

            // Skip events before cursor (defensive: Keycloak may return older events on first call)
            if (evTime < cursorMs) {
                continue;
            }
            // Skip exact-timestamp duplicates already ingested in a previous cycle
            if (evTime == cursorMs && cursorIds.contains(ev.getId())) {
                continue;
            }

            try {
                auditLogService.recordFromKeycloak(toRequest(ev));
                ingested++;
            } catch (Exception ex) {
                log.warn("[keycloak-poll] failed to record event id={} type={}: {}", ev.getId(), ev.getType(), ex.getMessage());
                continue;
            }

            // Advance cursor tracking
            if (evTime > newCursorMs) {
                newCursorMs = evTime;
                newCursorIds = new HashSet<>();
                newCursorIds.add(ev.getId());
            } else if (evTime == newCursorMs) {
                newCursorIds.add(ev.getId());
            }
        }

        if (ingested > 0) {
            log.info("[keycloak-poll] ingested {} events, cursor advanced to {}", ingested, Instant.ofEpochMilli(newCursorMs));
        } else {
            log.debug("[keycloak-poll] {} events fetched, 0 new after dedup", events.size());
        }

        cursorMs = newCursorMs;
        cursorIds = newCursorIds;
    }

    private KeycloakEventRequest toRequest(EventRepresentation ev) {
        return new KeycloakEventRequest(
            ev.getTime(),
            ev.getType(),
            ev.getRealmId(),
            ev.getClientId(),
            ev.getUserId(),
            ev.getSessionId(),
            ev.getIpAddress(),
            ev.getError(),
            ev.getDetails() == null ? null : Map.copyOf(ev.getDetails())
        );
    }

    private ApplicationProperties.Audit.KeycloakPoll pollConfig() {
        return props.audit().keycloakPoll();
    }
}
