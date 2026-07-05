package bo.com.sintesis.hub.base.interop.outbound.efxrate.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * DTO que representa la respuesta exitosa del endpoint externo de EfxRate.
 * Modela exactamente lo que llega por el cable desde el proveedor,
 * con los nombres de campo que la API externa entrega (snake_case en español).
 *
 * @param codMonedaOrigen  código de la moneda origen
 * @param codMonedaDestino código de la moneda destino
 * @param fechaVigencia    fecha de vigencia en formato "yyyyMMdd"
 * @param valor            valor del tipo de cambio con precisión original del proveedor
 * @param fuente           fuente del dato (p.ej. "BCB")
 * @param timestampUtc     timestamp ISO-8601 UTC de la publicación del dato
 */
public record EfxRateProviderResponse(
        @JsonProperty("cod_moneda_origen")  String codMonedaOrigen,
        @JsonProperty("cod_moneda_destino") String codMonedaDestino,
        @JsonProperty("fecha_vigencia")     String fechaVigencia,
        @JsonProperty("valor")              BigDecimal valor,
        @JsonProperty("fuente")             String fuente,
        @JsonProperty("timestamp_utc")      String timestampUtc
) {
}
