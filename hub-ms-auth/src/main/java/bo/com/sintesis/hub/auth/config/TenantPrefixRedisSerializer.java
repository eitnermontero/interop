package bo.com.sintesis.hub.auth.config;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;

/**
 * Antepone "{TENANT_ID}:" a cada key de Redis.
 * Cuando TENANT_ID está vacío opera sin prefijo (modo single-tenant).
 * <p>
 * Admin no usa L2 Hibernate (sin redisson), por lo que no incluye el NameMapper
 * de Redisson presente en cart/report.
 */
public class TenantPrefixRedisSerializer implements RedisSerializer<String> {

    private final byte[] prefix;
    private final String prefixStr;

    public TenantPrefixRedisSerializer(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            this.prefixStr = tenantId + ":";
            this.prefix = prefixStr.getBytes(StandardCharsets.UTF_8);
        } else {
            this.prefixStr = "";
            this.prefix = new byte[0];
        }
    }

    @Override
    public byte[] serialize(String value) throws SerializationException {
        if (value == null) return null;
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (prefix.length == 0) return raw;
        byte[] result = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(raw, 0, result, prefix.length, raw.length);
        return result;
    }

    @Override
    public String deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null) return null;
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (prefix.length == 0) return raw;
        return raw.startsWith(prefixStr) ? raw.substring(prefixStr.length()) : raw;
    }
}
