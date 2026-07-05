package bo.com.sintesis.hub.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the shared audit library.
 * Bound to the prefix {@code audit} in the host application's properties.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
    /** Global on/off switch. Default true. */
    Boolean enabled,

    /**
     * Transport mode. Values: remote (default) or in-process.
     * remote:     loads ServiceTokenProvider + AuditClient + RemoteHttpSink.
     * in-process: skips HTTP client; the host app must register an InProcessSink bean.
     */
    String sinkMode,

    /** Absolute URL of the audit ingest endpoint (POST). */
    String endpoint,

    /** OAuth2 client_credentials settings for the service-account JWT. */
    OAuth oauth,

    /** In-memory ring buffer size. Events dropped when full. */
    Integer bufferSize,

    /** Retry policy for transient failures. */
    Retry retry,

    /**
     * Configuración de integridad criptográfica para la cadena de hashes del hub.
     * Activa la firma del {@code chain_hash} con Vault Transit cuando
     * {@code vault-enabled=true}.
     */
    Integrity integrity
) {
    /**
     * Keycloak {@code client_credentials} settings. token-uri/client-id/client-secret
     * are derived in the host yml from the existing {@code keycloak.service-client.*}.
     */
    public record OAuth(
        String tokenUri,
        String clientId,
        String clientSecret,
        String scope
    ) {}

    public record Retry(
        Integer maxAttempts,
        Integer backoffMs
    ) {}

    /**
     * Propiedades de integración con Vault Transit para la firma del chain_hash.
     *
     * @param vaultEnabled  {@code true} activa la firma real; {@code false} (default) usa NoOp
     * @param vaultKeyName  nombre de la clave en Vault Transit; default {@code hub-audit-signing}
     * @param vaultPath     path del motor Vault Transit; default {@code transit}
     */
    public record Integrity(
        Boolean vaultEnabled,
        String vaultKeyName,
        String vaultPath
    ) {
        /** Retorna el nombre de clave efectivo (default {@code hub-audit-signing}). */
        public String effectiveKeyName() {
            return vaultKeyName != null && !vaultKeyName.isBlank()
                    ? vaultKeyName : "hub-audit-signing";
        }

        /** Retorna el path efectivo del motor Transit (default {@code transit}). */
        public String effectivePath() {
            return vaultPath != null && !vaultPath.isBlank()
                    ? vaultPath : "transit";
        }
    }
}
