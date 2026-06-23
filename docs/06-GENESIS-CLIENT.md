> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# 06 - Cliente HTTP hacia Genesis (sdk-intraplatinum-java)

## Objetivo

Integrar el SDK `sdk-intraplatinum-java` (`IntraPlatinumClient`) en `mwc-cart-service` para comunicarse con los servicios REST de `genesis_c`. El SDK encapsula la comunicación HTTP, reintentos, logging, manejo de sesiones y DTOs. Nuestro servicio se enfoca en: (1) configurar el SDK como Bean de Spring, (2) mapear respuestas del SDK al formato estandarizado de la API, y (3) convertir montos entre USDT y BOB.

## Credenciales Genesis por Partner

Cada partner tiene sus propias credenciales de acceso a Genesis (`usuario`, `password`, `metodo`, `oriTra`), almacenadas en Vault (`secret/middleware-core/partners/{partner_id}/genesis`). Al autenticar un partner via API Key, el servicio obtiene estas credenciales de Vault y crea un `IpcLoginRequest` para autenticarse contra Genesis via el SDK. Ver `09-VAULT.md`.

## Dependencia

```gradle
dependencies {
    implementation 'bo.com.sintesis:sdk-intraplatinum-java:1.x.x'
}
```

> **Nota:** El SDK usa internamente RestTemplate con Apache HttpClient5, Jackson para serialización, y SLF4J para logging. No introduce WebFlux ni reactor.

## Configuración del SDK como Spring Bean

### Propiedades (Consul KV)

```yaml
genesis:
  base-url: "https://test.sintesis.com.bo/genesis_c"
  timeout:
    connect-ms: 5000
    read-ms: 30000
  retry:
    max-attempts: 2
    delay-ms: 1000
  session:
    ttl-minutes: 10
  logging:
    enabled: true
    level: BASIC   # NONE | BASIC | HEADERS | FULL
```

### Bean Configuration

```java
@Configuration
public class GenesisClientConfig {

    @Bean
    public IntraPlatinumClient intraPlatinumClient(
            @Value("${genesis.base-url}") String baseUrl,
            @Value("${genesis.timeout.connect-ms}") int connectTimeout,
            @Value("${genesis.timeout.read-ms}") int readTimeout,
            @Value("${genesis.retry.max-attempts}") int maxRetries,
            @Value("${genesis.retry.delay-ms}") int retryDelay,
            @Value("${genesis.session.ttl-minutes}") int sessionTtlMinutes,
            @Value("${genesis.logging.enabled}") boolean loggingEnabled,
            @Value("${genesis.logging.level}") String logLevel) {

        IntraPlatinumConfig config = IntraPlatinumConfig
            .builder(baseUrl)
            .connectTimeout(Duration.ofMillis(connectTimeout))
            .readTimeout(Duration.ofMillis(readTimeout))
            .maxRetries(maxRetries)
            .retryDelay(Duration.ofMillis(retryDelay))
            .sessionTtl(Duration.ofMinutes(sessionTtlMinutes))
            .loggingEnabled(loggingEnabled)
            .logLevel(IntraPlatinumConfig.LogLevel.valueOf(logLevel))
            .build();

        return IntraPlatinumClient.create(config);
    }
}
```

## Capacidades del SDK

### Manejo Automático de Sesiones

El SDK incluye `IdSessionManager` que cachea sesiones por credencial (`usuario:password`) con TTL configurable. Esto significa que NO necesitamos manejar sesiones manualmente:

```java
// Opción 1: Pasar idSession explícito
client.getCatalogo().listarDepartamentos(idSession);

// Opción 2: Pasar credenciales — el SDK maneja la sesión automáticamente
IpcLoginRequest creds = new IpcLoginRequest()
    .setUsuario("partner_user")
    .setPassword("partner_pass");

client.getCatalogo().listarDepartamentos(creds);
```

Para nuestro caso, **usamos la Opción 2 (credenciales)** porque cada partner tiene credenciales distintas almacenadas en Vault, y el SDK se encarga de login + cache + renovación transparente.

### Reintentos Automáticos

El SDK implementa reintentos con backoff exponencial en errores de red (`IOException`). Configurable via `maxRetries` y `retryDelay`.

### Logging con Enmascaramiento

El SDK enmascara automáticamente datos sensibles en logs:
- **Request headers:** `authorization`, `x-api-key`, `x-session-id`
- **Request body:** `password`, `token`, `sessionId`, `secret`, `apiKey`
- **Response body:** `token`, `idSession`, `accessToken`, `secret`
- **Campos largos comprimidos:** `datoB64`, `urlBase64`, `description`, `details`

Niveles: `NONE` (sin logs), `BASIC` (método + URL + status), `HEADERS` (+ headers), `FULL` (+ body).

## Mapeo SDK → Endpoints de la API

| API Endpoint | Método SDK | API de SDK | Propósito |
|---|---|---|---|
| `POST /api/v1/auth/token` | `client.getAuth().login(creds)` | `AuthApi` | Autenticación |
| `GET /api/v1/departments` | `client.getCatalogo().listarDepartamentos(creds)` | `CatalogoApi` | Departamentos |
| `GET /api/v1/categories` | `client.getCatalogo().listarRubros(creds)` | `CatalogoApi` | Categorías |
| `GET /api/v1/providers` | `client.getCatalogo().listarModulosGrupos(creds)` | `CatalogoApi` | Proveedores con grupos |
| `GET /api/v1/providers/filter` | `client.getCatalogo().obtenerModulosFiltro(creds, rubro, dpto)` | `CatalogoApi` | Filtrar proveedores |
| `GET /api/v1/providers/{id}/groups` | `client.getCatalogo().buscarGrupos(creds, cliente)` | `CatalogoApi` | Grupos del proveedor |
| `GET /api/v1/providers/{id}/groups/{gid}/criteria` | `client.getCatalogo().buscarCriteriosGrupo(creds, cliente, codGrupo)` | `CatalogoApi` | Criterios de búsqueda |
| `GET /api/v1/providers/{id}/criteria/{cid}/fields` | `client.getCatalogo().buscarDetalleCriterio(creds, cliente, criterio)` | `CatalogoApi` | Campos del criterio |
| `POST /api/v1/accounts/search` | `client.getCuenta().buscarCuenta(creds, request)` | `CuentaApi` | Buscar cuenta |
| `POST /api/v1/accounts/items` | `client.getCuenta().obtenerItems(creds, request)` | `CuentaApi` | Items de una cuenta |
| `POST /api/v1/payments/init` | `client.getCarrito().iniciarCarrito(creds)` + `client.getCarrito().agregarItems(creds, request)` | `CarritoApi` | Crear carrito + agregar items (interno) |
| `POST /api/v1/payments/confirm` | `client.getPago().pagarCuenta(creds, request)` | `PagoApi` | Confirmar pago |
| `GET /api/v1/receipts` | `client.getPago().obtenerImpresion(creds, request)` | `PagoApi` | Comprobante |

## DTOs del SDK (Referencia)

### Respuesta Base

Todas las respuestas extienden `IpcBaseResponse`:

```java
public class IpcBaseResponse {
    private int codError;       // 0 = éxito
    private String error;       // "N" = sin error, "S" = con error
    private String mensaje;
    private String codRequisito;

    public boolean isSuccess() {
        return codError == 0 && "N".equals(error);
    }
}
```

El SDK lanza `IpcApiException` automáticamente cuando `isSuccess()` es `false`. No necesitamos validar manualmente.

### Requests Principales

| DTO SDK | Campos | Notas |
|---|---|---|
| `IpcLoginRequest` | `usuario`, `password` | `metodo` y `oriTra` se setean con defaults ("USUARIO", "GSIS") |
| `IpcSessionRequest` | `idSession`, `metodo` | Usado internamente por el SDK |
| `IpcObtModFiltroRequest` | `idSession`, `metodo`, `rubro`, `dpto` | Defaults: metodo="EJECUTAR", rubro=99, dpto=99 |
| `IpcBuscarGruposRequest` | `idSession`, `cliente` | — |
| `IpcBuscarCritGrpRequest` | `idSession`, `cliente`, `codGrupo` | — |
| `IpcBuscarDetCritRequest` | `idSession`, `cliente`, `criterio` | — |
| `IpcBuctaRequest` | `idSession`, `cliente`, `criterio`, `entities: List<IpcCampoValor>` | — |
| `IpcItemsRequest` | `idSession`, `cliente`, `cuenta`, `fechaOper`, `nroOperacion`, `servicio` | `fechaOper` formato: `20260309` |
| `IpcAddCarritoRequest` | `idSession`, `idCarrito`, `cliente`, `cuenta`, `fechaOper`, `nroOperacion`, `servicio`, `nombreFac`, `nroDoc`, `tipoDoc`, `complementoDoc`, `email`, `direcEnvio`, `entities: List<IpcItemMonto>`, `entities1: List<Object>` | `entities1` siempre `List.of()` |
| `IpcPagarCuentaRequest` | `idSession`, `idCarrito`, `cliente`, `cuenta`, `codSer`, `forPag`, `datosForPag: List<Object>` | — |
| `IpcObtenerImpresionRequest` | `idSession`, `cliente`, `codSer`, `cuenta`, `fechaOper`, `nroOperacion` | — |

### Responses Principales

| DTO SDK | Campos Relevantes |
|---|---|
| `IpcLoginResponse` | `idSession` |
| `IpcLisDptoResponse` | `entities: List<IpcDepartamento>` → `codDpto`, `descripcion`, `sigla` |
| `IpcLisRubrosResponse` | `entities: List<IpcRubro>` → `codRub`, `descripcion`, `imgUrl` |
| `IpcModulosGruposResponse` | `entities: List<IpcModulo>` → `codCliente`, `descrip`, `icono`, `grupos` |
| `IpcObtModFiltroResponse` | `entities: List<IpcModuloFiltrado>` → `cliente`, `descCliente`, `icono`, `rubro`, `entities1: List<IpcDptoDisponible>` |
| `IpcBuscarGruposResponse` | `entities: List<IpcGrupo>` → `codGrupo`, `descrip`, `tipo`, `icono` |
| `IpcBuscarCritGrpResponse` | `entities: List<IpcCriterio>` → `idCriterio`, `descrip` |
| `IpcBuscarDetCritResponse` | `entities: List<IpcCampo>` → `idCampo`, `etiqueta`, `tipo`, `mandatorio`, `longitud`, `validacion`, `codLista`, `dependencia` |
| `IpcBuctaResponse` | `nroOperacion`, `fechaOper`, `entities: List<IpcCuentaInfo>` → `cuenta`, `nombre`, `detalle`, `codSer`, `desSer`, `moneda` |
| `IpcItemsResponse` | `cambiaNombreNit`, `nitFac`, `nombreFac`, `entities: List<IpcItem>` → `nroItem`, `descrip`, `monedaItem`, `dependeDeItem`, `tipoDePago`, `monto`, `tipoComprobante` |
| `IpcIniCarritoResponse` | `idCarrito` |
| `IpcEstadoCarritoResponse` | `estCarrito`, `cont`, `entities: List<IpcCarritoEntry>` → `cliente`, `servicio`, `cuenta`, `total`, `moneda`, `fechaOper`, `nroOperacion` |
| `IpcAddCarritoResponse` | `entities: List<IpcCarritoEntry>` |
| `IpcPagarCuentaResponse` | (solo campos base) |
| `IpcObtenerImpresionResponse` | `entities1: List<IpcImpresionData>` → `datoB64`, `urlBase64`, `descripBase64`, `tipoB64` |

## Conversión de Montos (USDT ↔ BOB)

El SDK no maneja conversión de montos. Al agregar items al carrito (`agregarItems`), los montos deben enviarse en **BOB** a Genesis. La conversión USDT → BOB es responsabilidad de `mwc-cart-service`:

```java
public IpcAddCarritoRequest buildAddCartRequest(
        PaymentTransaction transaction, List<PaymentTransactionItem> items) {

    // Convertir montos del usuario → BOB usando el rate fijado en la transacción
    List<IpcItemMonto> genesisItems = items.stream()
        .map(item -> new IpcItemMonto()
            .setNroItem(item.getItemNumber())
            .setMonto(item.getLocalAmount().doubleValue()))
        .toList();

    return new IpcAddCarritoRequest()
        .setIdCarrito(transaction.getCartId())
        .setCliente(transaction.getProviderId())
        .setCuenta(transaction.getAccountNumber())
        .setFechaOper(transaction.getOperationDateAsInt())
        .setNroOperacion(transaction.getOperationNumber())
        .setServicio(transaction.getServiceCode())
        .setNombreFac(transaction.getInvoiceName())
        .setNroDoc(transaction.getInvoiceTaxId())
        .setTipoDoc(transaction.getDocumentType())
        .setComplementoDoc(transaction.getDocumentComplement())
        .setEmail(transaction.getEmail())
        .setDirecEnvio(apiRequest.getDeliveryAddress())
        .setEntities(items)
        .setEntities1(List.of());
}
```

## Manejo de Excepciones del SDK

El SDK usa una jerarquía de excepciones sellada:

```
IpcException (sealed)
├── IpcApiException        → Error de negocio de Genesis (codError != 0)
│   - getCodError(): int
│   - getEndpoint(): String
│   - getMessage(): String
├── IpcConfigException     → Error de configuración del SDK
├── IpcServerException     → Error de red/comunicación
│   └── IpcTimeoutException → Timeout de conexión o lectura
```

### Mapeo de Excepciones SDK → HTTP Response

| Excepción SDK | HTTP Response | Error Code | Detalle |
|---|---|---|---|
| `IpcApiException` | 502 Bad Gateway | `GENESIS_ERROR` | `"Backend service error: " + ex.getMessage()` |
| `IpcTimeoutException` | 504 Gateway Timeout | `GENESIS_TIMEOUT` | `"Genesis service timeout"` |
| `IpcServerException` | 503 Service Unavailable | `GENESIS_UNAVAILABLE` | `"Genesis service unavailable"` |
| `IpcConfigException` | 500 Internal Server Error | `INTERNAL_ERROR` | `"Genesis client configuration error"` |

### Exception Handler

```java
@RestControllerAdvice
public class GenesisExceptionHandler {

    @ExceptionHandler(IpcApiException.class)
    public ResponseEntity<ProblemDetail> handleGenesisApiError(IpcApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY,
            "Backend service error: " + ex.getMessage()
        );
        problem.setType(URI.create("https://middleware-core.sintesis.com.bo/problems/genesis-error"));
        problem.setTitle("Genesis Error");
        problem.setProperty("errorCode", "GENESIS_ERROR");
        problem.setProperty("genesisCode", ex.getCodError());
        return ResponseEntity.status(502).body(problem);
    }

    @ExceptionHandler(IpcTimeoutException.class)
    public ResponseEntity<ProblemDetail> handleGenesisTimeout(IpcTimeoutException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.GATEWAY_TIMEOUT,
            "Genesis service timeout"
        );
        problem.setType(URI.create("https://middleware-core.sintesis.com.bo/problems/genesis-error"));
        problem.setTitle("Genesis Timeout");
        problem.setProperty("errorCode", "GENESIS_TIMEOUT");
        return ResponseEntity.status(504).body(problem);
    }

    @ExceptionHandler(IpcServerException.class)
    public ResponseEntity<ProblemDetail> handleGenesisUnavailable(IpcServerException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Genesis service unavailable"
        );
        problem.setType(URI.create("https://middleware-core.sintesis.com.bo/problems/genesis-error"));
        problem.setTitle("Genesis Unavailable");
        problem.setProperty("errorCode", "GENESIS_UNAVAILABLE");
        return ResponseEntity.status(503).body(problem);
    }
}
```

## Servicio de Integración (Capa de Mapeo)

El servicio que usa el SDK no es el SDK directamente — es una capa que transforma entre DTOs del SDK y DTOs de nuestra API:

```java
@Service
@Slf4j
public class GenesisService {

    private final IntraPlatinumClient client;

    // Las credenciales se obtienen de Vault según el partner autenticado
    public List<DepartmentResponse> getDepartments(IpcLoginRequest creds) {
        IpcLisDptoResponse response = client.getCatalogo()
            .listarDepartamentos(creds);

        return response.getEntities().stream()
            .map(dpto -> new DepartmentResponse(
                dpto.getCodDpto(),
                dpto.getDescripcion(),
                dpto.getSigla()))
            .toList();
    }

    public List<CategoryResponse> getCategories(IpcLoginRequest creds) {
        IpcLisRubrosResponse response = client.getCatalogo()
            .listarRubros(creds);

        return response.getEntities().stream()
            .map(rubro -> new CategoryResponse(
                rubro.getCodRub(),
                rubro.getDescripcion(),
                rubro.getImgUrl()))
            .toList();
    }

    public CartCreateResponse createCart(IpcLoginRequest creds) {
        IpcIniCarritoResponse response = client.getCarrito()
            .iniciarCarrito(creds);

        return new CartCreateResponse(
            response.getIdCarrito(),
            "ACTIVE",
            Instant.now());
    }

    // ... demás métodos siguen el mismo patrón:
    // 1. Llamar al método del SDK con las credenciales
    // 2. Transformar la respuesta del SDK al DTO de nuestra API
}
```

## Flujo Completo de una Request

```
Partner → API Gateway → mwc-cart-service
                              │
                              ├─ 1. Extraer partner-id del JWT
                              ├─ 2. Obtener credenciales Genesis de Vault
                              ├─ 3. Crear IpcLoginRequest con credenciales
                              ├─ 4. Llamar método del SDK (sesión auto-gestionada)
                              ├─ 5. SDK → Genesis (HTTP POST)
                              ├─ 6. SDK valida respuesta (lanza IpcApiException si error)
                              ├─ 7. Mapear respuesta SDK → DTO de API
                              └─ 8. Retornar respuesta al partner
```

## Notas Importantes

1. **No implementar WebClient manual** — El SDK maneja toda la comunicación HTTP
2. **No implementar reintentos** — El SDK tiene `RetryInterceptor` configurable
3. **No implementar cache de sesiones** — El SDK tiene `IdSessionManager` con TTL
4. **No implementar DTOs de Genesis** — Usar los `Ipc*` del SDK directamente
5. **SI implementar:** capa de mapeo (SDK DTOs → API DTOs), conversión de montos (USDT ↔ BOB), exception handlers para excepciones del SDK
6. **Logging:** El SDK loguea requests/responses con enmascaramiento automático de datos sensibles. En producción usar nivel `BASIC` o `NONE`
7. **Thread safety:** `IntraPlatinumClient` y `IdSessionManager` son thread-safe (usan `ConcurrentHashMap`)
