package bo.com.sintesis.mdqr.audit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Resolves the HTTP status code from a method return value or thrown exception.
 * Used by AuditAspect to record the real response status rather than a hard-coded 200/500.
 */
public final class HttpStatusResolver {

    private HttpStatusResolver() {}

    /**
     * Resolves the HTTP status from a successful return value.
     * If the value is a ResponseEntity, reads its status code.
     * Falls back to 200.
     */
    public static int fromReturn(Object result) {
        if (result instanceof ResponseEntity<?> re) {
            return re.getStatusCode().value();
        }
        return 200;
    }

    /**
     * Resolves the HTTP status from a thrown exception.
     * Checks for @ResponseStatus annotation on the exception class (and its hierarchy).
     * Falls back to 500.
     */
    public static int fromException(Throwable ex) {
        ResponseStatus annotation = findResponseStatus(ex.getClass());
        if (annotation != null) {
            return annotation.value().value();
        }
        return 500;
    }

    private static ResponseStatus findResponseStatus(Class<?> type) {
        if (type == null || type == Object.class) return null;
        ResponseStatus ann = type.getAnnotation(ResponseStatus.class);
        if (ann != null) return ann;
        return findResponseStatus(type.getSuperclass());
    }
}
