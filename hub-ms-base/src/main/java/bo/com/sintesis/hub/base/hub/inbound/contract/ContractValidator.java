package bo.com.sintesis.hub.base.hub.inbound.contract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validador de payloads contra un {@link ContractDefinition}.
 *
 * <p>Sin efectos colaterales: no modifica el payload ni el contrato.
 * Puede usarse desde múltiples hilos sin estado compartido mutable.
 *
 * <p>Reglas por {@link FieldType}:
 * <ul>
 *   <li>{@code STRING} — el valor debe ser {@link String}; se verifica {@code maxLength} si está definido.</li>
 *   <li>{@code INTEGER} — el valor debe ser {@link Number} cuyo {@code longValue()} coincida con su {@code doubleValue()} (sin parte decimal).</li>
 *   <li>{@code BOOLEAN} — el valor debe ser {@link Boolean}.</li>
 *   <li>{@code DATETIME} — el valor debe ser {@link String} parseable como {@link OffsetDateTime}.</li>
 *   <li>{@code ARRAY} — el valor debe ser {@link java.util.List}.</li>
 * </ul>
 */
@Slf4j
@Component
public class ContractValidator {

    private static final String FORMAT_ISO8601 = "iso8601";

    /**
     * Valida el payload contra el contrato dado.
     *
     * @param payload  mapa de campos recibido del caller (puede tener claves extra, se ignoran)
     * @param contract definición del contrato a validar
     * @return lista de violaciones (vacía si el payload es válido)
     */
    public List<ConstraintViolation> validate(Map<String, Object> payload,
                                              ContractDefinition contract) {
        List<ConstraintViolation> violations = new ArrayList<>();

        for (FieldRule rule : contract.fields()) {
            Object value = payload.get(rule.field());

            // ── Requerido ────────────────────────────────────────────────────
            if (value == null) {
                if (rule.required()) {
                    violations.add(new ConstraintViolation(rule.field(), "El campo es requerido"));
                }
                // Campo opcional ausente → no validar tipo ni longitud
                continue;
            }

            // ── Tipo ─────────────────────────────────────────────────────────
            switch (rule.type()) {
                case STRING -> {
                    if (!(value instanceof String str)) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo debe ser de tipo texto (String)"));
                        break;
                    }
                    // maxLength
                    if (rule.maxLength() != null && str.length() > rule.maxLength()) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo supera la longitud máxima de " + rule.maxLength() + " caracteres"));
                    }
                }
                case INTEGER -> {
                    if (!(value instanceof Number num) || !esEntero(num)) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo debe ser de tipo entero (Integer)"));
                    }
                }
                case BOOLEAN -> {
                    if (!(value instanceof Boolean)) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo debe ser de tipo booleano (Boolean)"));
                    }
                }
                case DATETIME -> {
                    if (!(value instanceof String strDt)) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo debe ser una cadena de texto con formato fecha/hora"));
                        break;
                    }
                    // Siempre validamos ISO 8601 para DATETIME; el campo format es indicativo
                    if (!esFechaIso8601Valida(strDt)) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo debe tener formato ISO 8601 con offset (ej. 2025-01-15T10:30:00-04:00)"));
                    }
                }
                case ARRAY -> {
                    if (!(value instanceof List<?>)) {
                        violations.add(new ConstraintViolation(rule.field(),
                                "El campo debe ser de tipo arreglo (Array)"));
                    }
                }
            }
        }

        log.debug("Validación de contrato {}/{}: {} violaciones encontradas",
                contract.product(), contract.version(), violations.size());

        return violations;
    }

    // ─── helpers privados ─────────────────────────────────────────────────────

    /**
     * Verifica que un {@link Number} no tenga parte decimal significativa.
     * Jackson deserializa integers como {@link Integer} o {@link Long};
     * si el campo viene como {@code 3.0} (Double) también se acepta.
     */
    private boolean esEntero(Number num) {
        if (num instanceof Integer || num instanceof Long) {
            return true;
        }
        // Double/Float sin parte decimal (ej. 3.0 → válido como entero)
        double d = num.doubleValue();
        return d == Math.floor(d) && !Double.isInfinite(d) && !Double.isNaN(d);
    }

    private boolean esFechaIso8601Valida(String value) {
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
