package bo.com.sintesis.hub.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditEventPublisherTest {

    @Mock
    private AuditEventSink sink;

    private AuditEventPublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) publisher.stop();
    }

    private AuditEventDto event(String type) {
        return AuditEventDto.builder()
            .eventTime(Instant.now())
            .eventType(type)
            .module("TEST")
            .serviceName("test-service")
            .responseStatus(200)
            .build();
    }

    @Test
    void sink_exception_does_not_propagate_to_caller() throws Exception {
        doThrow(new RuntimeException("sink down")).when(sink).emit(any());
        publisher = new AuditEventPublisher(sink, 100, 1, 10L, null);
        publisher.start();

        assertThatNoException().isThrownBy(() -> publisher.publish(event("CREATE")));

        verify(sink, timeout(2000)).emit(any());
    }

    @Test
    void buffer_full_increments_drop_counter_and_logs_warning() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        publisher = new AuditEventPublisher(sink, 1, 3, 100L, registry);
        publisher.start();

        publisher.publish(event("CREATE"));
        publisher.publish(event("UPDATE"));

        double dropped = registry.find("audit.events.dropped").counter().count();
        assertThat(dropped).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void buffer_full_without_registry_does_not_throw() {
        publisher = new AuditEventPublisher(sink, 1, 3, 100L, null);
        publisher.start();

        publisher.publish(event("CREATE"));
        assertThatNoException().isThrownBy(() -> publisher.publish(event("UPDATE")));
    }

    @Test
    void event_is_delivered_via_sink() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        publisher = new AuditEventPublisher(sink, 100, 1, 10L, registry);
        publisher.start();

        publisher.publish(event("LOGIN"));

        verify(sink, timeout(2000)).emit(any());
    }
}
