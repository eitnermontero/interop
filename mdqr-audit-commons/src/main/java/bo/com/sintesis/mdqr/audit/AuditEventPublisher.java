package bo.com.sintesis.mdqr.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget publisher: enqueues audit events into an in-memory ring buffer
 * and ships them on a single background worker thread. Designed so that an
 * unreachable admin-service NEVER blocks the calling thread.
 *
 * Uses AuditEventSink as transport seam - RemoteHttpSink by default, InProcessSink
 * inside admin-service, or any future drop-in (e.g. RedisStreamSink).
 */
@Slf4j
public class AuditEventPublisher {

    private final AuditEventSink sink;
    private final int bufferSize;
    private final int maxAttempts;
    private final long backoffMs;
    private final MeterRegistry meterRegistry;

    private BlockingQueue<AuditEventDto> buffer;
    private Thread worker;
    private volatile boolean running;

    private Counter dropCounter;

    public AuditEventPublisher(AuditEventSink sink, int bufferSize, int maxAttempts,
                               long backoffMs, MeterRegistry meterRegistry) {
        this.sink = sink;
        this.bufferSize = bufferSize;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void start() {
        this.buffer = new LinkedBlockingQueue<>(bufferSize);
        this.running = true;

        if (meterRegistry != null) {
            this.dropCounter = Counter.builder("audit.events.dropped")
                .description("Number of audit events dropped due to full buffer")
                .register(meterRegistry);
        }

        this.worker = new Thread(this::drainLoop, "audit-publisher");
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("Audit publisher started -- buffer={}, maxAttempts={}, backoff={}ms",
            bufferSize, maxAttempts, backoffMs);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    /** Non-blocking enqueue. Drops the event if the buffer is full. */
    public void publish(AuditEventDto event) {
        if (buffer == null) return;
        if (!buffer.offer(event)) {
            log.warn("Audit buffer full -- dropping {} event for module={}",
                event.eventType(), event.module());
            if (dropCounter != null) {
                dropCounter.increment();
            }
        }
    }

    private void drainLoop() {
        while (running) {
            AuditEventDto event;
            try {
                event = buffer.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (event == null) continue;
            sendWithRetry(event);
        }
    }

    private void sendWithRetry(AuditEventDto event) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                sink.emit(event);
                return;
            } catch (Exception ex) {
                if (attempt == maxAttempts) {
                    log.error("Audit send failed after {} attempts ({}): {}",
                        attempt, event.eventType(), ex.getMessage());
                    return;
                }
                try {
                    Thread.sleep(backoffMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
