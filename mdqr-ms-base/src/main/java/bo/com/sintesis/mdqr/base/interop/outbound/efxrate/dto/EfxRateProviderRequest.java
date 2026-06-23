package bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO que representa los parámetros del endpoint externo de EfxRate.
 * Modela exactamente lo que sale por el cable hacia el proveedor,
 * con los nombres de campo que la API externa espera.
 *
 * @param monedaOrigen  código de la moneda origen según nomenclatura del proveedor
 * @param monedaDestino código de la moneda destino según nomenclatura del proveedor
 * @param fecha         fecha en formato "yyyyMMdd" según el contrato del proveedor
 */
public record EfxRateProviderRequest(
        @JsonProperty("moneda_origen")  String monedaOrigen,
        @JsonProperty("moneda_destino") String monedaDestino,
        @JsonProperty("fecha")          String fecha
) {
}
