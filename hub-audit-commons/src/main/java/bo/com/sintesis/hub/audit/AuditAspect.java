package bo.com.sintesis.hub.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class AuditAspect {

    private static final String MDC_REQUEST_ID = "requestId";

    private final AuditEventPublisher publisher;
    private final AuditContextExtractor contextExtractor;
    private final String serviceName;
    private final Environment environment;

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        long started = System.currentTimeMillis();
        AuditEventDto.AuditEventDtoBuilder builder = AuditEventDto.builder()
            .eventTime(Instant.now())
            .eventType(auditable.event())
            .module(auditable.module())
            .optionCode(auditable.option().isBlank() ? null : auditable.option())
            .serviceName(serviceName);

        try {
            contextExtractor.populate(builder);
        } catch (Exception ex) {
            log.debug("Audit context extraction failed: {}", ex.getMessage());
        }

        try {
            builder.tenantId(environment.getProperty("TENANT_ID"));
            builder.reqId(MDC.get(MDC_REQUEST_ID));
        } catch (Exception ex) {
            log.debug("Audit tenant/reqId extraction failed: {}", ex.getMessage());
        }

        if (auditable.includeRequestBody()) {
            builder.details(extractParams(pjp, auditable.excludeParams()));
        }

        try {
            Object result = pjp.proceed();
            int status = 200;
            try {
                status = HttpStatusResolver.fromReturn(result);
            } catch (Exception ex) {
                log.debug("Audit status resolution failed: {}", ex.getMessage());
            }
            publisher.publish(builder
                .responseStatus(status)
                .durationMs((int) (System.currentTimeMillis() - started))
                .build());
            return result;
        } catch (Throwable ex) {
            int status = 500;
            try {
                status = HttpStatusResolver.fromException(ex);
            } catch (Exception resolveEx) {
                log.debug("Audit exception status resolution failed: {}", resolveEx.getMessage());
            }
            Map<String, Object> errorDetails = new HashMap<>();
            errorDetails.put("error", ex.getClass().getSimpleName());
            errorDetails.put("message", ex.getMessage());
            publisher.publish(builder
                .responseStatus(status)
                .durationMs((int) (System.currentTimeMillis() - started))
                .details(errorDetails)
                .build());
            throw ex;
        }
    }

    private Map<String, Object> extractParams(ProceedingJoinPoint pjp, String[] excluded) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names  = sig.getParameterNames();
        Object[] values = pjp.getArgs();
        Set<String> skip = new HashSet<>();
        for (String e : excluded) skip.add(e);

        Map<String, Object> params = new HashMap<>();
        if (names == null) return params;
        for (int i = 0; i < names.length; i++) {
            if (skip.contains(names[i])) continue;
            Object v = values[i];
            params.put(names[i], v == null || isSimple(v) ? v : v.toString());
        }
        return params;
    }

    private boolean isSimple(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean
            || v instanceof Enum<?> || v instanceof java.time.temporal.Temporal;
    }
}
