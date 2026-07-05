package bo.com.sintesis.hub.audit.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Canonicalización de payloads JSON siguiendo RFC 8785 (JSON Canonicalization Scheme).
 *
 * <p>El proceso es:
 * <ol>
 *   <li>Parsear el JSON en un árbol {@link JsonNode}.</li>
 *   <li>Ordenar recursivamente las claves de cada {@link ObjectNode}
 *       lexicográficamente (comparación de puntos de código Unicode).</li>
 *   <li>Serializar sin espacios en blanco y sin caracteres de nueva línea.</li>
 *   <li>Retornar los bytes UTF-8 del JSON canónico.</li>
 * </ol>
 *
 * <p>Esta implementación no utiliza dependencias externas: únicamente Jackson
 * ({@code jackson-databind}). Soporta objetos, arrays, primitivos (string, number,
 * boolean) y null anidados a cualquier profundidad.
 *
 * <p>Si el payload no es JSON válido, se lanza {@link IllegalArgumentException}.
 */
public final class JsonCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private JsonCanonicalizer() {
    }

    /**
     * Canonicaliza el payload JSON dado y retorna sus bytes UTF-8.
     *
     * @param jsonPayload payload JSON como {@code String}; no puede ser nulo ni vacío
     * @return bytes UTF-8 del JSON canónico (RFC 8785)
     * @throws IllegalArgumentException si {@code jsonPayload} no es JSON válido
     */
    public static byte[] canonicalize(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            throw new IllegalArgumentException("El payload JSON no puede ser nulo o vacío");
        }
        try {
            JsonNode root = MAPPER.readTree(jsonPayload);
            JsonNode sorted = sortRecursively(root);
            return MAPPER.writeValueAsBytes(sorted);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "El payload proporcionado no es JSON válido: " + e.getMessage(), e);
        }
    }

    /**
     * Canonicaliza los bytes UTF-8 de un payload JSON.
     *
     * @param rawBytes bytes UTF-8 del payload JSON
     * @return bytes UTF-8 del JSON canónico (RFC 8785)
     * @throws IllegalArgumentException si los bytes no representan JSON válido
     */
    public static byte[] canonicalize(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("El array de bytes no puede ser nulo o vacío");
        }
        return canonicalize(new String(rawBytes, StandardCharsets.UTF_8));
    }

    /**
     * Ordena recursivamente las claves de cada ObjectNode en orden lexicográfico
     * (comparación de puntos de código Unicode, equivalente a String.compareTo en Java).
     * Los ArrayNode preservan el orden de sus elementos tal como está definido en RFC 8785.
     *
     * @param node nodo raíz del árbol JSON
     * @return nuevo árbol JSON con claves ordenadas
     */
    private static JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            // TreeMap ordena lexicográficamente por defecto (Comparable<String>)
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, value) -> result.set(key, sortRecursively(value)));
            return result;
        } else if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            List<JsonNode> elements = new ArrayList<>();
            node.elements().forEachRemaining(elements::add);
            // RFC 8785 §3.2.3: el orden de los arrays se preserva
            elements.stream()
                    .map(JsonCanonicalizer::sortRecursively)
                    .forEach(result::add);
            return result;
        } else {
            // Primitivos (string, number, boolean, null): retornar tal cual
            return node;
        }
    }
}
