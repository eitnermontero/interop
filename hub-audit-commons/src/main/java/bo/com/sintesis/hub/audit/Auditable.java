package bo.com.sintesis.hub.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method whose invocations should be audited.
 * The {@code AuditAspect} intercepts the call and publishes an event to
 * the centralized audit log via {@link AuditEventPublisher}.
 *
 * <pre>
 * &#64;Auditable(module = "REPORTES_SOBOCE", option = "COMPROBANTE_PDF", event = "EXPORT")
 * public ResponseEntity&lt;byte[]&gt; exportPdf(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** Functional area, e.g. {@code REPORTES_SOBOCE}, {@code PAGOS}, {@code USUARIOS}. */
    String module();

    /** Specific feature inside the module. Optional, defaults to empty. */
    String option() default "";

    /** Event verb, e.g. {@code READ}, {@code CREATE}, {@code EXPORT}, {@code LOGIN}. */
    String event();

    /** When true, request parameter values are copied into the {@code details} map. */
    boolean includeRequestBody() default false;

    /** Parameter names whose values must NEVER be audited (passwords, tokens, secrets). */
    String[] excludeParams() default {};
}
