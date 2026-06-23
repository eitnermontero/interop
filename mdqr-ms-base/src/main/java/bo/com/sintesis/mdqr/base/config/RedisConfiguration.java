package bo.com.sintesis.mdqr.base.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Configuración de Redis para caché del hub.
 * <p>
 * Configura:
 * - CacheManager con TTL de 24 horas (constante; ver ADR-0004)
 * - RedisTemplate con serialización JSON
 * - StringRedisSerializer para las keys
 * </p>
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisConfiguration {

    // TTL por defecto 24 h. Desacoplado de ApplicationProperties.Qr (ADR-0004 paso 1).
    private static final int DEFAULT_CACHE_TTL_MINUTES = 1440;

    /**
     * Configura el CacheManager de Redis con TTL de 24 horas.
     *
     * @param connectionFactory Factory de conexión a Redis
     * @return CacheManager configurado
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        int ttlMinutes = DEFAULT_CACHE_TTL_MINUTES;

        log.info("Configurando Redis CacheManager con TTL de {} minutos", ttlMinutes);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(ttlMinutes))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(createJsonSerializer())
            )
            .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .transactionAware()
            .build();
    }

    /**
     * Configura RedisTemplate para operaciones manuales de caché.
     * Usa StringRedisSerializer para keys y GenericJackson2JsonRedisSerializer para values.
     *
     * @param connectionFactory Factory de conexión a Redis
     * @return RedisTemplate configurado
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Serializer para las keys (String)
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        // Serializer para los values (JSON)
        GenericJackson2JsonRedisSerializer valueSerializer = createJsonSerializer();
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.setEnableTransactionSupport(true);
        template.afterPropertiesSet();

        log.info("RedisTemplate configurado con serialización JSON");

        return template;
    }

    /**
     * Crea un serializador JSON para Redis con soporte de tipos polimórficos.
     *
     * @return GenericJackson2JsonRedisSerializer configurado
     */
    private GenericJackson2JsonRedisSerializer createJsonSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();

        // Registrar módulo para soporte de Java 8 Date/Time API
        objectMapper.registerModule(new JavaTimeModule());

        // Configurar TypeValidator para seguridad
        BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(Object.class)
            .build();

        // Activar tipado polimórfico para deserialización correcta
        objectMapper.activateDefaultTyping(
            typeValidator,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }
}
