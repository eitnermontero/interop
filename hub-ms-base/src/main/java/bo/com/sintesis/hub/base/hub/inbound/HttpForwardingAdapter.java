package bo.com.sintesis.hub.base.hub.inbound;

import bo.com.sintesis.hub.base.hub.inbound.config.HubInteropProperties;
import bo.com.sintesis.hub.base.hub.inbound.port.ForwardResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptador HTTP genérico del motor inbound (ADR-0007 fase 2, versión mínima).
 *
 * <p>Único {@code InboundPort} de propósito general: reenvía el payload ya
 * validado al conector declarado en {@code hub.connectors}, sin código por
 * producto. La URL efectiva es {@code base-url + target-path}, con placeholders
 * {@code {campo}} resueltos desde el payload (p. ej. {@code /casos/{id_pol_caso}}).
 *
 * <p>Para contratos de solo lectura ({@code method: GET}) el reenvío se hace sin
 * body: las claves del payload (originadas en los query params de la petición
 * entrante, ver {@code DispatcherController#get}) que calzan con un placeholder
 * de {@code target-path} lo resuelven (p. ej. {@code /operativos/{cud}}, consulta
 * de detalle); las que no se usan como placeholder se reenvían tal cual como
 * query string del request saliente (p. ej. {@code pagina}/{@code limite} de un
 * listado). Los catálogos sin parámetros simplemente no tienen claves que reenviar.
 *
 * <p>Semántica de errores (catálogo ADR-0005 §7):
 * <ul>
 *   <li>Destino alcanzado pero respondió error → 502 (UPSTREAM_ERROR).</li>
 *   <li>Destino inalcanzable o timeout → 503 (SERVICE_UNAVAILABLE).</li>
 * </ul>
 *
 * <p>Pendiente fase 2 completa: resiliencia resilience4j por conector (patrón
 * {@code EfxRateClient}) y auth {@code API_KEY} resuelta desde Vault.
 */
@Slf4j
public class HttpForwardingAdapter {

    /** Clientes HTTP cacheados por nombre de conector (config inmutable en runtime v1). */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    /**
     * Reenvía el payload al conector.
     *
     * @param connectorName nombre lógico del conector (clave en {@code hub.connectors})
     * @param connector     configuración del destino
     * @param api           configuración de la API (método, target-path)
     * @param payload       payload ya validado por el {@code ContractValidator}
     * @param correlationId ID de correlación a propagar
     */
    public ForwardResult forward(String connectorName,
                                 HubInteropProperties.ConnectorProps connector,
                                 HubInteropProperties.ApiProps api,
                                 Map<String, Object> payload,
                                 String correlationId) {

        RestClient client = clients.computeIfAbsent(connectorName, n -> buildClient(connector));
        HttpMethod httpMethod = HttpMethod.valueOf(api.getMethod().toUpperCase());

        Set<String> usadasComoPlaceholder = new HashSet<>();
        String path = resolverPlaceholders(api.getTargetPath(), payload, usadasComoPlaceholder);
        // Claves del payload (query params de la petición entrante) que no se
        // consumieron como placeholder de path se reenvían tal cual como query
        // string del request saliente (p. ej. pagina/limite de un listado).
        Map<String, Object> queryParamsSobrantes = (httpMethod == HttpMethod.GET)
                ? sobrantes(payload, usadasComoPlaceholder)
                : Map.of();

        try {
            // toEntity(Object.class): la forma real de la respuesta depende del destino —
            // un objeto JSON ({...}) deserializa a LinkedHashMap, un array JSON ([...])
            // deserializa a ArrayList (p. ej. catálogos GET que responden un array plano).
            // Map.class forzaba la deserialización a objeto y fallaba con "Error while
            // extracting response" en cualquier destino que respondiera un array.
            ResponseEntity<Object> response = (httpMethod == HttpMethod.GET)
                    // GET: sin body — los catálogos de solo lectura no tienen payload de entrada.
                    // uri(Function<UriBuilder,URI>): deja que el propio RestClient codifique
                    // los query params (evita el doble-encoding de armar el query string a mano).
                    ? client.method(httpMethod)
                            .uri(uriBuilder -> {
                                uriBuilder.path(path);
                                queryParamsSobrantes.forEach(uriBuilder::queryParam);
                                return uriBuilder.build();
                            })
                            .header("X-Correlation-ID", correlationId)
                            .retrieve()
                            .toEntity(Object.class)
                    : client.method(httpMethod)
                            .uri(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Correlation-ID", correlationId)
                            .body(payload)
                            .retrieve()
                            .toEntity(Object.class);

            Object data = response.getBody() != null ? response.getBody() : Map.of();
            log.debug("Forward OK: connector={} path={} status={} correlationId={}",
                    connectorName, path, response.getStatusCode().value(), correlationId);
            return new ForwardResult(true, response.getStatusCode().value(), data,
                    "Aceptado por el destino '" + connectorName + "'");

        } catch (RestClientResponseException e) {
            // Destino alcanzado pero respondió error → UPSTREAM_ERROR (502)
            log.warn("Upstream error: connector={} path={} status={} correlationId={}",
                    connectorName, path, e.getStatusCode().value(), correlationId);
            return new ForwardResult(false, 502, Map.of(),
                    "El destino '" + connectorName + "' respondió con error " + e.getStatusCode().value());

        } catch (ResourceAccessException e) {
            // Timeout o conexión rechazada → SERVICE_UNAVAILABLE (503)
            log.warn("Destino no disponible: connector={} path={} causa={} correlationId={}",
                    connectorName, path, e.getMessage(), correlationId);
            return new ForwardResult(false, 503, Map.of(),
                    "El destino '" + connectorName + "' no está disponible");
        }
    }

    private RestClient buildClient(HubInteropProperties.ConnectorProps connector) {
        if (!"NONE".equalsIgnoreCase(connector.getAuth().getType())) {
            // Fase 2 pendiente: resolver credencial desde Vault (patrón EfxRateClient)
            log.warn("Auth '{}' aún no soportada por el adaptador genérico — se conecta sin credencial",
                    connector.getAuth().getType());
        }
        // JdkClientHttpRequestFactory (java.net.http.HttpClient): a diferencia de
        // SimpleClientHttpRequestFactory (HttpURLConnection), soporta PATCH.
        // version(HTTP_1_1): sin fijarla, el HttpClient intenta negociar HTTP/2 por
        // defecto; contra backends que solo hablan HTTP/1.1 (el caso típico de las
        // instituciones partner) eso produce conexiones truncadas/EOF intermitentes
        // (reproducido con WireMock al escribir los tests de este adaptador).
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connector.getTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(connector.getTimeoutMs()));
        return RestClient.builder()
                .baseUrl(connector.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Reemplaza cada {@code {campo}} del path con el valor correspondiente del payload.
     *
     * @param usadasComoPlaceholder se completa con las claves del payload que sí se
     *                              usaron para resolver un placeholder — permite al
     *                              caller distinguir el resto (query string en GET).
     */
    private static String resolverPlaceholders(String targetPath, Map<String, Object> payload,
                                                Set<String> usadasComoPlaceholder) {
        if (targetPath == null) {
            return "";
        }
        String resolved = targetPath;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            if (targetPath.contains(placeholder)) {
                resolved = resolved.replace(placeholder, String.valueOf(e.getValue()));
                usadasComoPlaceholder.add(e.getKey());
            }
        }
        return resolved;
    }

    /** Claves del payload que no se usaron como placeholder de path (candidatas a query string). */
    private static Map<String, Object> sobrantes(Map<String, Object> payload, Set<String> usadasComoPlaceholder) {
        Map<String, Object> resultado = new HashMap<>();
        payload.forEach((key, value) -> {
            if (!usadasComoPlaceholder.contains(key) && value != null) {
                resultado.put(key, value);
            }
        });
        return resultado;
    }
}
