> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# 04 - Temporizador de Transacción (Redis TTL)

## Objetivo

Implementar un temporizador parametrizable que controle la vigencia de una transacción de pago tras iniciarla via `POST /api/v1/payments/init`. Pasado el tiempo (default 5 minutos), la transacción expira automáticamente debido a la volatilidad del tipo de cambio.

## Configuración (`application.yml`)

```yaml
payment:
  transaction:
    timeout-seconds: 300          # 5 minutos por defecto
    grace-period-seconds: 10      # Período de gracia para confirm en curso
    max-timeout-seconds: 900      # Máximo permitido (15 min)
    min-timeout-seconds: 60       # Mínimo permitido (1 min)
```

## Arquitectura

```
┌──────────────┐                    ┌────────────────────┐
│ POST         │   Fijar rate +     │      Redis         │
│ /payments/   │   crear carrito +  │                    │
│ init         │   iniciar timer    │ txn_timer:         │
│              │───────────────────▶│  {transactionId}   │
└──────────────┘                    │  TTL: 300s         │
                                    │  (parametrizable)  │
┌──────────────┐                    │                    │
│ POST         │   Verificar timer  │ txn_status:        │
│ /payments/   │───────────────────▶│  {transactionId}   │
│ confirm      │   ¿Expirado?       │  TTL: 3600s        │
│              │   → 408            │                    │
└──────────────┘                    └───────┬────────────┘
                                            │
                               ┌────────────▼──────────┐
                               │  Redis Keyspace       │
                               │  Notifications        │
                               │  (key expired event)  │
                               └────────────┬──────────┘
                                            │
                               ┌────────────▼───────────────┐
                               │  TransactionExpirationListener│
                               │  - Marcar transacción como   │
                               │    EXPIRED en PostgreSQL      │
                               │  - Log auditoría              │
                               └───────────────────────────────┘
```

## Modelo Redis

### Timer de Transacción

```
Key:    txn_timer:{transactionId}
Value:  {
            "transactionId": "txn_6609a2f7b3e4c81d5a0f9e72",
            "cartId": "2026030900000019",
            "partnerId": "550e8400-e29b-41d4-a716-446655440001",
            "exchangeRate": "6.960000000000",
            "userCurrency": "USDT",
            "startedAt": "2026-03-09T15:30:00Z",
            "expiresAt": "2026-03-09T15:35:00Z",
            "durationSeconds": 300
        }
TTL:    300 segundos (parametrizable via application.yml: payment.transaction.timeout-seconds)
```

### Estado de la Transacción

```
Key:    txn_status:{transactionId}
Value:  "INITIATED" | "CONFIRMED" | "EXPIRED"
TTL:    3600 segundos (1 hora, para consultas posteriores)
```

## Flujo del Temporizador

```
1. Cliente llama POST /api/v1/payments/init
   ├── Se obtiene tipo de cambio de Currency Engine (vinculante)
   ├── Se convierte montos USDT→BOB
   ├── Se llama SDK: iniciarCarrito → obtener cartId
   ├── Se llama SDK: agregarItems → agregar items con montos en BOB
   ├── Se persiste transacción en PostgreSQL (montos USDT 12 decimales + rate)
   ├── Se crea key txn_timer:{transactionId} en Redis con TTL = timeout-seconds
   ├── Se crea key txn_status:{transactionId} = "INITIATED"
   └── Se retorna transacción con expiresAt

2. Cliente confirma: POST /api/v1/payments/confirm
   ├── Se verifica txn_timer:{transactionId} existe en Redis
   ├── Si NO existe → timer expirado → 408 TRANSACTION_EXPIRED
   ├── Si existe → se usa el exchangeRate fijado en la transacción
   ├── Se llama SDK: pagarCuenta con datos persistidos
   ├── Se actualiza transacción en PostgreSQL: status = CONFIRMED
   ├── Se actualiza txn_status:{transactionId} = "CONFIRMED"
   └── Se elimina txn_timer:{transactionId} (transacción completada)

3. Timer expira (Redis keyspace notification)
   ├── TransactionExpirationListener recibe evento expired
   ├── Actualiza txn_status:{transactionId} = "EXPIRED"
   ├── Actualiza transacción en PostgreSQL: status = EXPIRED
   └── Log auditoría
```

## Redis Keyspace Notifications

Configuración necesaria en Redis:

```
CONFIG SET notify-keyspace-events Ex
```

Listener en Spring:

```java
@Component
public class TransactionExpirationListener implements MessageListener {

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();

        if (expiredKey.startsWith("txn_timer:")) {
            String transactionId = expiredKey.replace("txn_timer:", "");
            handleTransactionExpiration(transactionId);
        }
    }

    private void handleTransactionExpiration(String transactionId) {
        // Actualizar estado en Redis
        redisTemplate.opsForValue().set(
            "txn_status:" + transactionId, "EXPIRED",
            Duration.ofHours(1)
        );

        // Actualizar estado en PostgreSQL
        paymentTransactionRepository.updateStatus(transactionId, "EXPIRED");

        log.info("Transaction {} expired", transactionId);
    }
}
```

## Configuración Spring para Keyspace Notifications

```java
@Bean
public RedisMessageListenerContainer redisContainer(
        RedisConnectionFactory connectionFactory,
        TransactionExpirationListener listener) {

    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(listener,
        new PatternTopic("__keyevent@0__:expired"));
    return container;
}
```

## Verificación del Timer en el Servicio

```java
@Service
public class TransactionTimerService {

    public TransactionTimerInfo verifyTimer(String transactionId) {
        String timerKey = "txn_timer:" + transactionId;
        String timerData = redisTemplate.opsForValue().get(timerKey);

        if (timerData == null) {
            // Timer expirado
            throw new TransactionExpiredException(transactionId,
                "Transaction has expired due to exchange rate volatility");
        }

        Long ttl = redisTemplate.getExpire(timerKey, TimeUnit.SECONDS);
        TransactionTimerInfo info = deserialize(timerData);
        info.setRemainingSeconds(ttl != null ? ttl : 0);

        return info;
    }

    public void completeTimer(String transactionId) {
        redisTemplate.delete("txn_timer:" + transactionId);
        redisTemplate.opsForValue().set(
            "txn_status:" + transactionId, "CONFIRMED",
            Duration.ofHours(1)
        );
    }
}
```

## Consideraciones

1. **Período de gracia:** Si un confirm está en curso y el timer expira durante la transacción, el `grace-period-seconds` (10s) da margen. Se implementa verificando el timer al inicio del confirm y usando un lock distribuido en Redis durante la operación.

2. **Atomicidad:** El init crea carrito + agrega items + inicia timer en una sola operación. Si falla cualquier paso (ej: Genesis error en add-carrito), se hace rollback completo (no queda carrito huérfano en Genesis sin timer).

3. **Persistencia:** El timer vive en Redis (volátil). La transacción completa se persiste en PostgreSQL. Si Redis se reinicia, todos los timers activos se pierden y las transacciones INITIATED se consideran expiradas.

4. **Idempotencia y timer:** Si un request idempotente retorna una respuesta cacheada, NO se reinicia el timer. El `expiresAt` original se mantiene.
