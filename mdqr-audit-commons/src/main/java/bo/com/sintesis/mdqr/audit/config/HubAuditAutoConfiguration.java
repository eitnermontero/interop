package bo.com.sintesis.mdqr.audit.config;

import bo.com.sintesis.mdqr.audit.chain.ChainHashCalculator;
import bo.com.sintesis.mdqr.audit.hash.PayloadHasher;
import bo.com.sintesis.mdqr.audit.hub.HubAuditService;
import bo.com.sintesis.mdqr.audit.signing.AuditSigner;
import bo.com.sintesis.mdqr.audit.signing.NoOpAuditSigner;
import bo.com.sintesis.mdqr.audit.signing.VaultTransitAuditSigner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.vault.core.VaultTransitOperations;

/**
 * Auto-configuración condicional del nucleo de auditoría del hub.
 *
 * <p>Se activa únicamente si {@code JdbcTemplate} está en el classpath del consumer
 * (es decir, si el consumer tiene spring-jdbc en sus dependencias) y si la
 * propiedad {@code audit.enabled} es {@code true} (default).
 *
 * <p>Registra los siguientes beans si no existen ya:
 * <ul>
 *   <li>{@link ChainHashCalculator} — calcula el chain_hash según RFC 8785.</li>
 *   <li>{@link PayloadHasher} — calcula hashes SHA-256 de payloads JSON.</li>
 *   <li>{@link AuditSigner} — Vault Transit si {@code audit.integrity.vault-enabled=true};
 *       {@link NoOpAuditSigner} en caso contrario.</li>
 *   <li>{@link HubAuditService} — servicio central de escritura atómica.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class HubAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChainHashCalculator chainHashCalculator() {
        return new ChainHashCalculator();
    }

    @Bean
    @ConditionalOnMissingBean
    public PayloadHasher payloadHasher() {
        return new PayloadHasher();
    }

    /**
     * AuditSigner con Vault Transit: se activa cuando la clase
     * {@code VaultTransitOperations} está en el classpath Y la propiedad
     * {@code audit.integrity.vault-enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(VaultTransitOperations.class)
    @ConditionalOnProperty(prefix = "audit.integrity", name = "vault-enabled", havingValue = "true")
    public AuditSigner vaultAuditSigner(VaultTransitOperations vaultTransit,
                                         AuditProperties props) {
        String keyName = props.integrity() != null
                ? props.integrity().effectiveKeyName()
                : "hub-audit-signing";
        return new VaultTransitAuditSigner(vaultTransit, keyName);
    }

    /**
     * AuditSigner NoOp: fallback cuando Vault está deshabilitado o su clase
     * no está en el classpath. El orden de evaluacion de {@code @ConditionalOnMissingBean}
     * garantiza que este bean solo se crea si el Vault signer no fue creado.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditSigner noOpAuditSigner() {
        return new NoOpAuditSigner();
    }

    @Bean
    @ConditionalOnMissingBean
    public HubAuditService hubAuditService(JdbcTemplate jdbcTemplate,
                                            ChainHashCalculator chainHashCalculator,
                                            AuditSigner signer) {
        return new HubAuditService(jdbcTemplate, chainHashCalculator, signer);
    }
}
