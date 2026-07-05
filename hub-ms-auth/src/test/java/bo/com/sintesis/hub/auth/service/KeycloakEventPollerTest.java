package bo.com.sintesis.hub.auth.service;

import bo.com.sintesis.hub.auth.config.ApplicationProperties;
import bo.com.sintesis.hub.auth.config.ApplicationProperties.Audit;
import bo.com.sintesis.hub.auth.config.ApplicationProperties.Audit.KeycloakPoll;
import bo.com.sintesis.hub.auth.repository.AuditLogRepository;
import bo.com.sintesis.hub.auth.service.dto.KeycloakEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.EventRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakEventPollerTest {

    @Mock private RealmResource realm;
    @Mock private AuditLogService auditLogService;
    @Mock private AuditLogRepository auditLogRepository;

    private ApplicationProperties props;
    private KeycloakEventPoller poller;

    @BeforeEach
    void setUp() {
        props = buildProps(true, 60_000L, 200);
        poller = new KeycloakEventPoller(realm, auditLogService, auditLogRepository, props);
    }

    // ── initCursor ─────────────────────────────────────────────────────────────

    @Test
    void initCursor_loads_max_event_time_from_db_when_present() {
        Instant dbMax = Instant.parse("2025-01-10T12:00:00Z");
        when(auditLogRepository.findMaxEventTimeByServiceName(KeycloakEventPoller.SERVICE_NAME))
            .thenReturn(Optional.of(dbMax));

        poller.initCursor();

        // The cursor should be the DB max; verify the poll derives dateFrom from it in the
        // yyyy-MM-dd format that the Keycloak Admin API expects (not an ISO timestamp).
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of());

        poller.doPoll();

        ArgumentCaptor<String> dateFromCaptor = ArgumentCaptor.forClass(String.class);
        verify(realm).getEvents(any(), isNull(), isNull(), dateFromCaptor.capture(), isNull(), isNull(), eq(0), eq(200));
        assertThat(dateFromCaptor.getValue()).isEqualTo("2025-01-10");
    }

    @Test
    void initCursor_uses_fallback_when_no_prior_keycloak_events() {
        when(auditLogRepository.findMaxEventTimeByServiceName(KeycloakEventPoller.SERVICE_NAME))
            .thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> poller.initCursor());
    }

    // ── doPoll — mapping and recording ─────────────────────────────────────────

    @Test
    void doPoll_records_login_event_via_recordFromKeycloak() {
        // Cursor from DB ensures event time >= cursor
        long t = System.currentTimeMillis() + 1_000;
        when(auditLogRepository.findMaxEventTimeByServiceName(any()))
            .thenReturn(Optional.of(Instant.ofEpochMilli(t - 5_000)));
        poller.initCursor();

        EventRepresentation ev = loginEvent("id-1", t, "LOGIN", "user-a", "sess-1", "1.2.3.4");
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(ev));

        poller.doPoll();

        ArgumentCaptor<KeycloakEventRequest> captor = ArgumentCaptor.forClass(KeycloakEventRequest.class);
        verify(auditLogService).recordFromKeycloak(captor.capture());

        KeycloakEventRequest req = captor.getValue();
        assertThat(req.type()).isEqualTo("LOGIN");
        assertThat(req.userId()).isEqualTo("user-a");
        assertThat(req.sessionId()).isEqualTo("sess-1");
        assertThat(req.ipAddress()).isEqualTo("1.2.3.4");
        assertThat(req.time()).isEqualTo(t);
    }

    @Test
    void doPoll_records_logout_event() {
        long t = System.currentTimeMillis() + 1_000;
        when(auditLogRepository.findMaxEventTimeByServiceName(any()))
            .thenReturn(Optional.of(Instant.ofEpochMilli(t - 5_000)));
        poller.initCursor();

        EventRepresentation ev = loginEvent("id-2", t, "LOGOUT", "user-b", "sess-2", "5.6.7.8");
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(ev));

        poller.doPoll();

        ArgumentCaptor<KeycloakEventRequest> captor = ArgumentCaptor.forClass(KeycloakEventRequest.class);
        verify(auditLogService).recordFromKeycloak(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("LOGOUT");
    }

    @Test
    void doPoll_records_multiple_events_in_batch() {
        long base = System.currentTimeMillis() + 1_000;
        when(auditLogRepository.findMaxEventTimeByServiceName(any()))
            .thenReturn(Optional.of(Instant.ofEpochMilli(base - 10_000)));
        poller.initCursor();

        List<EventRepresentation> events = List.of(
            loginEvent("id-1", base,          "LOGIN",  "user-a", "sess-1", "1.1.1.1"),
            loginEvent("id-2", base + 1_000,  "LOGOUT", "user-b", "sess-2", "2.2.2.2"),
            loginEvent("id-3", base + 2_000,  "LOGIN",  "user-c", "sess-3", "3.3.3.3")
        );
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(events);

        poller.doPoll();

        verify(auditLogService, times(3)).recordFromKeycloak(any());
    }

    @Test
    void doPoll_does_nothing_when_keycloak_returns_empty_list() {
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.empty());
        poller.initCursor();

        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of());

        poller.doPoll();

        verify(auditLogService, never()).recordFromKeycloak(any());
    }

    @Test
    void doPoll_does_nothing_when_keycloak_returns_null() {
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.empty());
        poller.initCursor();

        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(null);

        assertThatNoException().isThrownBy(() -> poller.doPoll());
        verify(auditLogService, never()).recordFromKeycloak(any());
    }

    // ── Dedup ──────────────────────────────────────────────────────────────────

    @Test
    void doPoll_deduplicates_event_at_cursor_time_already_seen() {
        long cursorTime = 5_000L;
        Instant cursorInstant = Instant.ofEpochMilli(cursorTime);
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.of(cursorInstant));
        poller.initCursor();

        // First poll: one event exactly at cursor time
        EventRepresentation ev1 = loginEvent("id-cursor", cursorTime, "LOGIN", "user-x", "s-x", "1.1.1.1");
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(ev1));

        poller.doPoll();
        // The event is at cursor time and id not yet in cursorIds — it should be ingested
        verify(auditLogService, times(1)).recordFromKeycloak(any());

        // Second poll: same event returned again (same id, same time)
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(ev1));

        poller.doPoll();
        // id-cursor is now in cursorIds — must be skipped
        verify(auditLogService, times(1)).recordFromKeycloak(any());
    }

    @Test
    void doPoll_skips_events_before_cursor() {
        long cursorTime = 10_000L;
        when(auditLogRepository.findMaxEventTimeByServiceName(any()))
            .thenReturn(Optional.of(Instant.ofEpochMilli(cursorTime)));
        poller.initCursor();

        // Event before cursor should be skipped
        EventRepresentation old = loginEvent("id-old", cursorTime - 1, "LOGIN", "user-z", "s-z", "0.0.0.0");
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(old));

        poller.doPoll();

        verify(auditLogService, never()).recordFromKeycloak(any());
    }

    @Test
    void doPoll_advances_cursor_after_processing_new_events() {
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.empty());
        poller.initCursor();

        long t1 = System.currentTimeMillis() - 5_000;
        long t2 = t1 + 2_000;
        EventRepresentation e1 = loginEvent("id-a", t1, "LOGIN",  "u1", "s1", "1.1.1.1");
        EventRepresentation e2 = loginEvent("id-b", t2, "LOGOUT", "u2", "s2", "2.2.2.2");

        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(e1, e2))
            .thenReturn(List.of());

        poller.doPoll();
        verify(auditLogService, times(2)).recordFromKeycloak(any());

        // Second poll: e2 is at new cursor time but id already tracked — should NOT re-ingest
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(e2));

        poller.doPoll();
        // Still only 2 total calls — e2 was deduplicated
        verify(auditLogService, times(2)).recordFromKeycloak(any());
    }

    // ── Resilience ─────────────────────────────────────────────────────────────

    @Test
    void poll_does_not_propagate_exception_when_keycloak_api_fails() {
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.empty());
        poller.initCursor();

        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("Keycloak unavailable"));

        assertThatNoException().isThrownBy(() -> poller.poll());
    }

    @Test
    void poll_does_not_propagate_exception_when_recordFromKeycloak_fails() {
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.empty());
        poller.initCursor();

        EventRepresentation ev = loginEvent("id-err", System.currentTimeMillis(), "LOGIN", "u", "s", "1.1.1.1");
        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(List.of(ev));
        org.mockito.Mockito.doThrow(new RuntimeException("DB error")).when(auditLogService).recordFromKeycloak(any());

        assertThatNoException().isThrownBy(() -> poller.poll());
    }

    @Test
    void poll_wraps_doPoll_and_suppresses_all_exceptions() {
        when(auditLogRepository.findMaxEventTimeByServiceName(any())).thenReturn(Optional.empty());
        poller.initCursor();

        when(realm.getEvents(any(), isNull(), isNull(), anyString(), isNull(), isNull(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("network error"));

        assertThatNoException().isThrownBy(() -> poller.poll());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private EventRepresentation loginEvent(String id, long time, String type, String userId,
                                            String sessionId, String ipAddress) {
        EventRepresentation ev = new EventRepresentation();
        ev.setId(id);
        ev.setTime(time);
        ev.setType(type);
        ev.setUserId(userId);
        ev.setSessionId(sessionId);
        ev.setIpAddress(ipAddress);
        ev.setDetails(Map.of("username", "test-user"));
        return ev;
    }

    private ApplicationProperties buildProps(boolean enabled, long intervalMs, int maxEvents) {
        KeycloakPoll pollConfig = new KeycloakPoll(enabled, intervalMs, maxEvents);
        Audit audit = new Audit("dev-secret", 50_000, pollConfig);
        ApplicationProperties.Keycloak keycloak = new ApplicationProperties.Keycloak(
            "http://localhost:8180", "hub-admin", "hubadminservice",
            new ApplicationProperties.Keycloak.Credentials("secret"),
            new ApplicationProperties.Keycloak.AdminClient(
                "http://localhost:8180", "master", "admin-cli", null, "client_credentials")
        );
        return new ApplicationProperties(keycloak, "http://localhost:8080", audit);
    }
}
