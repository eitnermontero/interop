package bo.com.sintesis.hub.auth.web.rest.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ProblemWriter {

    static final String PROBLEMS_BASE = "https://api.sintesis.com.bo/problems/";

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public Map<String, Object> problem(ErrorCode code) {
        String detail = messageSource.getMessage(
            "error." + code.name(),
            null,
            LocaleContextHolder.getLocale()
        );
        return build(code.getStatus(), code.getTypeSlug(), code.getTitle(), detail, code.name());
    }

    public Map<String, Object> problem(ErrorCode code, String detail) {
        return build(code.getStatus(), code.getTypeSlug(), code.getTitle(), detail, code.name());
    }

    public void write(HttpServletResponse response, Map<String, Object> problem) throws IOException {
        int status = (int) problem.get("status");
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private Map<String, Object> build(HttpStatus status, String type, String title, String detail, String errorCode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", PROBLEMS_BASE + type);
        map.put("title", title);
        map.put("status", status.value());
        map.put("detail", detail);
        map.put("errorCode", errorCode);
        map.put("timestamp", Instant.now().toString());
        return map;
    }
}
