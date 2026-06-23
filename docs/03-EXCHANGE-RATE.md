> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# 03 - Servicio de Tipo de Cambio USDT/BOB (Currency Engine)

## Objetivo

Obtener el tipo de cambio USDT/BOB en tiempo real mediante una llamada REST al servicio **Currency Engine** y usarlo para convertir montos de BOB a USDT en las respuestas de la API.

## Arquitectura

```
┌──────────────────────────────────────────────────────────────────┐
│                     mwc-cart-service                              │
│                                                                  │
│  ExchangeRateService                                             │
│    │                                                             │
│    │  GET /currency-engine/api/v1/products/CARRITO_SUS           │
│    ▼                                                             │
│  ┌──────────────────┐                                            │
│  │ Currency Engine   │  REST API (Spring WebClient)              │
│  │ (servicio externo)│                                           │
│  └──────────────────┘                                            │
│           │                                                      │
│           ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐     │
│  │ Uso:                                                    │     │
│  │ 1. accounts/items → indicativeRate (no vinculante)      │     │
│  │ 2. payments/init  → exchange_rate (vinculante,          │     │
│  │                      persistido en payment_transaction) │     │
│  └──────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────┘
```

## Currency Engine API

### Request

```bash
curl -X GET https://qa.sintesis.com.bo/currency-engine/api/v1/products/CARRITO_SUS
```

### Response

```json
{
    "name": "CARRITO_SUS",
    "bidPrice": 5,
    "bidSpreadPercent": 0,
    "askPrice": 5,
    "askSpreadPercent": 0,
    "currency": "BOB",
    "symbol": "USDT",
    "lastUpdated": "2026-03-26T13:55:07.467391Z"
}
```

### Campos relevantes

| Campo | Descripción | Uso |
|---|---|---|
| `bidPrice` | Precio de compra (BOB por USDT) | Para conversión BOB → USDT |
| `askPrice` | Precio de venta (BOB por USDT) | Para conversión USDT → BOB |
| `currency` | Moneda base | BOB |
| `symbol` | Moneda cotizada | USDT |
| `lastUpdated` | Última actualización del rate | Validación de frescura |

### Lógica de conversión

- **BOB → USDT** (mostrar al partner): `amount_usdt = amount_bob / bidPrice`
- **USDT → BOB** (enviar a Genesis para pago): `amount_bob = amount_usdt * askPrice`

## Integración como Spring Service

El tipo de cambio se obtiene por **consumo directo** via REST. Cada vez que se necesita el rate (al consultar items o al iniciar una transacción), se invoca el servicio Currency Engine en el momento.

```java
@Service
public class ExchangeRateService {

    private final WebClient currencyEngineClient;

    public ExchangeRateResponse getCurrentRate() {
        ExchangeRateResponse response = currencyEngineClient
            .get()
            .uri("/currency-engine/api/v1/products/CARRITO_SUS")
            .retrieve()
            .bodyToMono(ExchangeRateResponse.class)
            .block();

        if (response == null || response.getBidPrice() == null) {
            throw new ExchangeRateUnavailableException(
                "Currency Engine returned null for CARRITO_SUS");
        }

        return response;
    }
}
```

## Configuración (Consul KV)

```yaml
exchange-rate:
  base-url: https://qa.sintesis.com.bo
  product-code: CARRITO_SUS          # Código del producto en Currency Engine
  timeout-ms: 5000
  retry-attempts: 3
  retry-delay-ms: 2000
  max-stale-minutes: 5               # Máximo tiempo sin actualización antes de rechazar
```

> **Nota:** La URL base y el código de producto son configurables en Consul KV para poder cambiarlos sin redespliegue. En producción, la URL base apuntará al ambiente productivo.

## Uso en el Flujo de Pagos

El tipo de cambio se usa en dos momentos:

1. **`POST /api/v1/accounts/items`** — Se consulta Currency Engine y se retorna como `indicativeRate` (no vinculante). El partner calcula la conversión indicativa en su lado.
2. **`POST /api/v1/payments/init`** — Se consulta Currency Engine, se fija como **vinculante** y se persiste en `payment_transaction.exchange_rate` con 12 decimales. Este rate se usa para convertir montos del usuario (USDT) a moneda local (BOB) para Genesis.

> **No hay tabla de quotes separada.** El tipo de cambio se almacena directamente en la transacción de pago.

## Manejo de Fallos

| Escenario | Acción |
|---|---|
| Currency Engine no responde o lanza excepción | Retornar `503` con `EXCHANGE_RATE_UNAVAILABLE`. No usar fallback estático |
| Response con `lastUpdated` más viejo que `max-stale-minutes` | Retornar `503`. El rate es demasiado viejo para operar |
| Transacción expirada | Retornar `408` con `TRANSACTION_EXPIRED`. Cliente debe iniciar nueva transacción |

> **Nota:** Al ser consumo directo sin cache, si Currency Engine falla se bloquean las operaciones que requieren conversión. Esto es intencional: operar con un rate desactualizado genera riesgo financiero.

## Precisión Numérica

- Tipo de cambio: `DECIMAL(24,12)` en PostgreSQL (`payment_transaction.exchange_rate`), `BigDecimal` en Java
- Montos del usuario (USDT): **12 decimales** — `DECIMAL(30,12)`, string en JSON
- Montos locales (BOB): 2 decimales — `DECIMAL(18,2)`, se preservan sin modificación para Genesis
