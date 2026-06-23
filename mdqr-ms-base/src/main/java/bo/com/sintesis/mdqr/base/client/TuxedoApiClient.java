package bo.com.sintesis.mdqr.base.client;

import bo.com.sintesis.mdqr.base.config.ApplicationProperties;
import bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException;
import bo.com.sintesis.mdqr.base.service.exception.TuxedoApiException;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Cliente HTTP para comunicarse con la API de Tuxedo (Go API).
 * <p>
 * La API de Tuxedo gestiona el JKS (KeyStore) en el servidor Tuxedo
 * y proporciona operaciones CRUD sobre certificados.
 * </p>
 */
@Component
@Slf4j
public class TuxedoApiClient {

    private final RestClient restClient;
    private final ApplicationProperties applicationProperties;

    public TuxedoApiClient(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;

        int timeoutMs = applicationProperties.getTuxedo().getTimeoutMs();

        // Configurar timeout en el HttpClient
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
            .baseUrl(applicationProperties.getTuxedo().getApiUrl())
            .defaultHeader("Authorization", "Bearer " + applicationProperties.getTuxedo().getApiKey())
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .requestFactory(requestFactory)
            .build();

        log.info("TuxedoApiClient inicializado. Base URL: {}, Timeout: {}ms",
            applicationProperties.getTuxedo().getApiUrl(),
            timeoutMs);
    }

    /**
     * Obtiene el contenido PEM de un certificado específico desde el JKS.
     *
     * @param alias Alias del certificado en el JKS
     * @return String PEM del certificado
     * @throws MissingCertificateException si el certificado no existe
     * @throws TuxedoApiException si hay error de comunicación
     */
    public String getCertificatePem(String alias) {
        try {
            log.debug("Obteniendo certificado PEM desde Tuxedo API: alias={}", alias);

            CertResponse response = restClient.get()
                .uri("/api/certs/{alias}", alias)
                .retrieve()
                .body(CertResponse.class);

            if (response == null || response.getPem() == null) {
                throw new TuxedoApiException("Respuesta vacía de Tuxedo API para alias: " + alias);
            }

            log.info("Certificado PEM obtenido exitosamente: alias={}", alias);
            return response.getPem();

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Certificado no encontrado en Tuxedo API: alias={}", alias);
            throw MissingCertificateException.forAlias(alias);

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Error HTTP al llamar Tuxedo API: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            throw TuxedoApiException.serverError(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (ResourceAccessException e) {
            log.error("Timeout o error de red al llamar Tuxedo API: {}", e.getMessage());
            throw TuxedoApiException.networkError(e);

        } catch (Exception e) {
            log.error("Error inesperado al llamar Tuxedo API: {}", e.getMessage(), e);
            throw new TuxedoApiException("Error inesperado al obtener certificado: " + e.getMessage(), e);
        }
    }

    /**
     * Lista todos los certificados disponibles en el JKS.
     *
     * @return Lista de certificados con información básica
     * @throws TuxedoApiException si hay error de comunicación
     */
    public List<CertListItem> listCertificates() {
        try {
            log.debug("Listando certificados desde Tuxedo API");

            CertListResponse response = restClient.get()
                .uri("/api/certs")
                .retrieve()
                .body(CertListResponse.class);

            if (response == null || response.getCertificates() == null) {
                log.warn("Respuesta vacía al listar certificados");
                return List.of();
            }

            log.info("Certificados listados exitosamente: {} certificados", response.getCertificates().size());
            return response.getCertificates();

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Error HTTP al listar certificados: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            throw TuxedoApiException.serverError(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (ResourceAccessException e) {
            log.error("Timeout o error de red al listar certificados: {}", e.getMessage());
            throw TuxedoApiException.networkError(e);

        } catch (Exception e) {
            log.error("Error inesperado al listar certificados: {}", e.getMessage(), e);
            throw new TuxedoApiException("Error inesperado al listar certificados: " + e.getMessage(), e);
        }
    }

    /**
     * Importa un nuevo certificado al JKS.
     *
     * @param alias Alias para el certificado
     * @param pem Contenido PEM del certificado
     * @param entityId ID de la entidad asociada
     * @return Respuesta con información del certificado importado
     * @throws TuxedoApiException si hay error de comunicación
     */
    public ImportCertResponse importCertificate(String alias, String pem, String entityId) {
        try {
            log.debug("Importando certificado a Tuxedo API: alias={}, entityId={}", alias, entityId);

            ImportCertRequest request = ImportCertRequest.builder()
                .alias(alias)
                .pem(pem)
                .entityId(entityId)
                .build();

            ImportCertResponse response = restClient.post()
                .uri("/api/certs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ImportCertResponse.class);

            if (response == null) {
                throw new TuxedoApiException("Respuesta vacía al importar certificado");
            }

            log.info("Certificado importado exitosamente: alias={}, serial={}", alias, response.getSerialNumber());
            return response;

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Error HTTP al importar certificado: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            throw TuxedoApiException.serverError(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (ResourceAccessException e) {
            log.error("Timeout o error de red al importar certificado: {}", e.getMessage());
            throw TuxedoApiException.networkError(e);

        } catch (Exception e) {
            log.error("Error inesperado al importar certificado: {}", e.getMessage(), e);
            throw new TuxedoApiException("Error inesperado al importar certificado: " + e.getMessage(), e);
        }
    }

    /**
     * Revoca (elimina) un certificado del JKS.
     *
     * @param alias Alias del certificado a revocar
     * @throws MissingCertificateException si el certificado no existe
     * @throws TuxedoApiException si hay error de comunicación
     */
    public void revokeCertificate(String alias) {
        try {
            log.debug("Revocando certificado en Tuxedo API: alias={}", alias);

            restClient.delete()
                .uri("/api/certs/{alias}", alias)
                .retrieve()
                .toBodilessEntity();

            log.info("Certificado revocado exitosamente: alias={}", alias);

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Certificado no encontrado al intentar revocar: alias={}", alias);
            throw MissingCertificateException.forAlias(alias);

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Error HTTP al revocar certificado: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            throw TuxedoApiException.serverError(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (ResourceAccessException e) {
            log.error("Timeout o error de red al revocar certificado: {}", e.getMessage());
            throw TuxedoApiException.networkError(e);

        } catch (Exception e) {
            log.error("Error inesperado al revocar certificado: {}", e.getMessage(), e);
            throw new TuxedoApiException("Error inesperado al revocar certificado: " + e.getMessage(), e);
        }
    }

    /**
     * Obtiene el hash SHA-256 del archivo JKS completo.
     * Utilizado para detectar cambios en el JKS.
     *
     * @return Hash SHA-256 en formato hexadecimal
     * @throws TuxedoApiException si hay error de comunicación
     */
    public String getJksHash() {
        try {
            log.debug("Obteniendo hash del JKS desde Tuxedo API");

            KeystoreInfoResponse response = restClient.get()
                .uri("/api/keystore/info")
                .retrieve()
                .body(KeystoreInfoResponse.class);

            if (response == null || response.getHash() == null) {
                throw new TuxedoApiException("Respuesta vacía al obtener hash del JKS");
            }

            log.debug("Hash del JKS obtenido: {}", response.getHash());
            return response.getHash();

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("Error HTTP al obtener hash del JKS: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            throw TuxedoApiException.serverError(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (ResourceAccessException e) {
            log.error("Timeout o error de red al obtener hash del JKS: {}", e.getMessage());
            throw TuxedoApiException.networkError(e);

        } catch (Exception e) {
            log.error("Error inesperado al obtener hash del JKS: {}", e.getMessage(), e);
            throw new TuxedoApiException("Error inesperado al obtener hash del JKS: " + e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // DTOs internos
    // ═════════════════════════════════════════════════════════════════════

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CertResponse {
        private String pem;
        private String alias;
        @JsonProperty("serial_number")
        private String serialNumber;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CertListResponse {
        private List<CertListItem> certificates;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CertListItem {
        private String alias;
        @JsonProperty("serial_number")
        private String serialNumber;
        private String subject;
        private String issuer;
        @JsonProperty("valid_from")
        private String validFrom;
        @JsonProperty("valid_to")
        private String validTo;
        @JsonProperty("entity_id")
        private String entityId;
        private String sha1;  // SHA-1 fingerprint del certificado
        private String sha256; // SHA-256 fingerprint del certificado
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ImportCertRequest {
        private String alias;
        private String pem;
        @JsonProperty("entity_id")
        private String entityId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportCertResponse {
        private Boolean success;
        private String alias;
        @JsonProperty("serial_number")
        private String serialNumber;
        private String message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeystoreInfoResponse {
        private String hash;
        private String path;
        @JsonProperty("cert_count")
        private Integer certCount;
    }
}
