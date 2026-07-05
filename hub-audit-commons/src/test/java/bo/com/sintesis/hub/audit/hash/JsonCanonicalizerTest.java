package bo.com.sintesis.hub.audit.hash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCanonicalizerTest {

    @Test
    void shouldSortKeysAlphabeticallyForFlatObject() {
        String json = "{\"z\":1,\"a\":2,\"m\":3}";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo("{\"a\":2,\"m\":3,\"z\":1}");
    }

    @Test
    void shouldSortKeysRecursivelyForNestedObject() {
        String json = "{\"outer\":{\"z\":true,\"a\":false},\"b\":1}";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        // "b" < "outer"; dentro de "outer": "a" < "z"
        assertThat(canonical).isEqualTo("{\"b\":1,\"outer\":{\"a\":false,\"z\":true}}");
    }

    @Test
    void shouldPreserveArrayOrder() {
        String json = "{\"items\":[3,1,2]}";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        // RFC 8785 §3.2.3: el orden de los arrays no se modifica
        assertThat(canonical).isEqualTo("{\"items\":[3,1,2]}");
    }

    @Test
    void shouldHandleNullValues() {
        String json = "{\"b\":null,\"a\":\"valor\"}";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo("{\"a\":\"valor\",\"b\":null}");
    }

    @Test
    void shouldProduceSameBytesForEquivalentJsonWithDifferentKeyOrder() {
        String json1 = "{\"partner\":\"unilink\",\"amount\":100,\"currency\":\"BOB\"}";
        String json2 = "{\"currency\":\"BOB\",\"partner\":\"unilink\",\"amount\":100}";
        byte[] result1 = JsonCanonicalizer.canonicalize(json1);
        byte[] result2 = JsonCanonicalizer.canonicalize(json2);
        assertThat(result1).isEqualTo(result2);
    }

    @Test
    void shouldHandleUnicodeCorrectly() {
        // Claves con caracteres unicode — ordenadas por punto de codigo Unicode
        String json = "{\"ñoño\":1,\"abc\":2}";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        // 'a' (U+0061) < 'ñ' (U+00F1), por lo tanto "abc" va primero
        assertThat(canonical).isEqualTo("{\"abc\":2,\"ñoño\":1}");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionForInvalidJson() {
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize("no es json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es JSON válido");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionForNullInput() {
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize((String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleArrayOfObjectsWithUnsortedKeys() {
        String json = "{\"list\":[{\"z\":1,\"a\":2},{\"y\":3,\"b\":4}]}";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        // Los objetos dentro del array también deben tener claves ordenadas
        assertThat(canonical).isEqualTo("{\"list\":[{\"a\":2,\"z\":1},{\"b\":4,\"y\":3}]}");
    }

    @Test
    void shouldProduceOutputWithoutWhitespace() {
        String json = "{ \"key\" : \"value\" , \"num\" : 42 }";
        byte[] result = JsonCanonicalizer.canonicalize(json);
        String canonical = new String(result, StandardCharsets.UTF_8);
        assertThat(canonical).doesNotContain(" ");
        assertThat(canonical).doesNotContain("\n");
    }
}
