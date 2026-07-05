package bo.com.sintesis.hub.auth.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Payload format emitted by the Keycloak HTTP Event Listener SPI.
 * Field names follow Keycloak's native event model (snake_case);
 * unknown properties are tolerated so a Keycloak upgrade doesn't break ingestion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakEventRequest(
    Long time,
    String type,
    String realmId,
    String clientId,
    String userId,
    String sessionId,
    String ipAddress,
    String error,
    Map<String, String> details
) {}
