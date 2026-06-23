package bo.com.sintesis.mdqr.base.interop.outbound.efxrate.mapper;

import bo.com.sintesis.mdqr.base.interop.canonical.ExchangeRateRequest;
import bo.com.sintesis.mdqr.base.interop.canonical.ExchangeRateResponse;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderRequest;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Traduce entre el contrato canónico interno y el contrato del proveedor EfxRate.
 *
 * Responsabilidad única: transformación de tipos y nombres de campo.
 * Sin lógica de negocio, sin llamadas HTTP, sin efectos secundarios.
 */
public class EfxRateMapper {

    private static final DateTimeFormatter PROVEEDOR_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Traduce la petición canónica interna al formato que el proveedor EfxRate espera.
     *
     * @param canonical petición canónica de la aplicación interna
     * @return DTO listo para ser serializado y enviado al proveedor
     */
    public EfxRateProviderRequest toProviderRequest(ExchangeRateRequest canonical) {
        return new EfxRateProviderRequest(
                canonical.baseCurrency(),
                canonical.targetCurrency(),
                canonical.date().format(PROVEEDOR_DATE_FORMAT)
        );
    }

    /**
     * Traduce la respuesta del proveedor al contrato canónico interno.
     *
     * @param provider respuesta tal como llegó del proveedor (post-deserialización)
     * @return respuesta canónica que la aplicación interna consume
     */
    public ExchangeRateResponse toCanonical(EfxRateProviderResponse provider) {
        LocalDate fechaVigencia = LocalDate.parse(provider.fechaVigencia(), PROVEEDOR_DATE_FORMAT);

        return new ExchangeRateResponse(
                provider.valor(),
                fechaVigencia,
                provider.codMonedaOrigen(),
                provider.codMonedaDestino(),
                provider.fuente(),
                Instant.now()
        );
    }
}
