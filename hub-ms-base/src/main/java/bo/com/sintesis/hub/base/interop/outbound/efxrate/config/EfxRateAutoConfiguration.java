package bo.com.sintesis.hub.base.interop.outbound.efxrate.config;

import bo.com.sintesis.hub.audit.hash.PayloadHasher;
import bo.com.sintesis.hub.audit.hub.HubAuditService;
import bo.com.sintesis.hub.base.config.ApplicationProperties;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.EfxRateAdapter;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.EfxRateClient;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.EfxRateProperties;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.mapper.EfxRateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.vault.core.VaultTemplate;

/**
 * Configuración explícita del adaptador outbound EfxRate.
 *
 * Los beans EfxRateClient y EfxRateAdapter se declaran aquí (no como @Component)
 * para mantener la instanciación explícita, facilitar los tests con mocks
 * y evitar que el @ComponentScan del módulo base los descubra automáticamente.
 *
 * VaultTemplate se inyecta como @Nullable porque en el perfil local
 * (vault.enabled=false) Spring Cloud Vault no registra ese bean.
 * En producción, la ausencia de VaultTemplate provocará una excepción
 * al intentar resolver el API key, lo cual es el comportamiento correcto.
 */
@Configuration
@EnableConfigurationProperties(EfxRateProperties.class)
@Slf4j
public class EfxRateAutoConfiguration {

    @Bean
    public EfxRateMapper efxRateMapper() {
        return new EfxRateMapper();
    }

    @Bean
    public EfxRateClient efxRateClient(EfxRateProperties properties,
                                        @Nullable VaultTemplate vaultTemplate,
                                        ApplicationProperties applicationProperties) {
        if (vaultTemplate == null) {
            log.warn("VaultTemplate no disponible: EfxRateClient usará API key de fallback local. " +
                     "Este modo NO debe usarse en producción.");
        }
        // En local se usa el API key del bloque localDevToken como fallback genérico.
        // TODO(hub-poc): cuando haya un secreto dedicado en Vault para local, eliminar este fallback.
        String fallbackApiKey = applicationProperties.getLocalDevToken().getApiKey();
        return new EfxRateClient(properties, vaultTemplate, fallbackApiKey);
    }

    /**
     * RedisTemplate String/String para el caché del adaptador EfxRate.
     *
     * @ConditionalOnBean(RedisConnectionFactory.class): solo se crea si hay un
     *   RedisConnectionFactory disponible (es decir, si Redis está activo). Si Redis
     *   está excluido (tests de integración del client), este bean no se crea.
     * @ConditionalOnMissingBean(name): no sobreescribe si ya existe un bean con ese
     *   nombre (p.ej. el mock de @TestConfiguration en los tests del adaptador).
     */
    @Bean("efxRateStringRedisTemplate")
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(name = "efxRateStringRedisTemplate")
    public RedisTemplate<String, String> efxRateStringRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public EfxRateAdapter efxRateAdapter(EfxRateClient efxRateClient,
                                          EfxRateMapper efxRateMapper,
                                          HubAuditService hubAuditService,
                                          PayloadHasher payloadHasher,
                                          EfxRateProperties properties,
                                          @Nullable
                                          @org.springframework.beans.factory.annotation.Qualifier(
                                                  "efxRateStringRedisTemplate")
                                          RedisTemplate<String, String> efxRateStringRedisTemplate) {
        if (efxRateStringRedisTemplate == null) {
            log.warn("Redis no disponible: EfxRateAdapter operará sin caché. " +
                     "En producción Redis debe estar activo.");
        }
        return new EfxRateAdapter(
                efxRateClient,
                efxRateMapper,
                hubAuditService,
                payloadHasher,
                properties,
                efxRateStringRedisTemplate
        );
    }
}
