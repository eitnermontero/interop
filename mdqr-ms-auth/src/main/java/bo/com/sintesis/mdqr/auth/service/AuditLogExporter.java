package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.domain.AuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuditLogExporter {

    private static final String[] HEADERS = {
        "id", "event_time", "event_type", "module", "option_code",
        "user_id", "username", "full_name", "roles",
        "ip_address", "user_agent", "service_name",
        "http_method", "endpoint", "response_status", "duration_ms", "details"
    };

    private final ObjectMapper objectMapper;

    public byte[] toCsv(List<AuditLog> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            // U+FEFF UTF-8 BOM: Excel needs it to auto-detect UTF-8 on Windows
            w.write('\uFEFF');
            w.write(String.join(",", HEADERS));
            w.write('\n');
            for (AuditLog r : rows) {
                writeRow(w, r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void writeRow(Writer w, AuditLog r) throws IOException {
        w.write(csv(String.valueOf(r.getId()))); w.write(',');
        w.write(csv(r.getEventTime() == null ? "" : DateTimeFormatter.ISO_INSTANT.format(r.getEventTime()))); w.write(',');
        w.write(csv(r.getEventType())); w.write(',');
        w.write(csv(r.getModule())); w.write(',');
        w.write(csv(r.getOptionCode())); w.write(',');
        w.write(csv(r.getUserId())); w.write(',');
        w.write(csv(r.getUsername())); w.write(',');
        w.write(csv(r.getFullName())); w.write(',');
        w.write(csv(r.getRoles() == null ? "" : String.join(";", r.getRoles()))); w.write(',');
        w.write(csv(r.getIpAddress())); w.write(',');
        w.write(csv(r.getUserAgent())); w.write(',');
        w.write(csv(r.getServiceName())); w.write(',');
        w.write(csv(r.getHttpMethod())); w.write(',');
        w.write(csv(r.getEndpoint())); w.write(',');
        w.write(csv(r.getResponseStatus() == null ? "" : r.getResponseStatus().toString())); w.write(',');
        w.write(csv(r.getDurationMs()    == null ? "" : r.getDurationMs().toString())); w.write(',');
        w.write(csv(serializeDetails(r))); w.write('\n');
    }

    private String serializeDetails(AuditLog r) {
        if (r.getDetails() == null) return "";
        try {
            return objectMapper.writeValueAsString(r.getDetails());
        } catch (IOException ex) {
            return "{}";
        }
    }

    /** RFC 4180 quoting: wrap in quotes if value contains comma, quote, CR or LF. */
    private String csv(String value) {
        if (value == null) return "";
        boolean needsQuotes = value.indexOf(',')  >= 0
                           || value.indexOf('"')  >= 0
                           || value.indexOf('\n') >= 0
                           || value.indexOf('\r') >= 0;
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
