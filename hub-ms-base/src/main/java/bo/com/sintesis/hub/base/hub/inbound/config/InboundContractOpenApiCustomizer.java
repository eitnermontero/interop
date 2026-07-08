package bo.com.sintesis.hub.base.hub.inbound.config;

import bo.com.sintesis.hub.base.hub.inbound.contract.ContractDefinition;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.hub.base.hub.inbound.contract.FieldRule;
import bo.com.sintesis.hub.base.hub.inbound.contract.FieldType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customizer de OpenAPI que genera los paths de la API inbound dinámicamente
 * a partir de los contratos registrados en {@link ContractRegistry} (ADR-0007 fase 6).
 *
 * <p>Para cada {@link ContractDefinition} presente en el registry en el momento del
 * arranque genera:
 * <ul>
 *   <li>Productos <b>sin</b> sufijo {@code _EDITAR} y {@code httpMethod=POST}:
 *       {@code POST /api/inbound/{product}/{version}} con el schema construido desde
 *       los {@link FieldRule} del contrato.</li>
 *   <li>Productos <b>con</b> sufijo {@code _EDITAR}:
 *       {@code PATCH /api/inbound/{productBase}/{version}/{id}} — {@code productBase}
 *       es el producto sin el sufijo, y el parámetro de path {@code id} documenta el
 *       campo que el dispatcher inyecta bajo {@code resourceIdField}.</li>
 *   <li>Productos de solo lectura ({@link ContractDefinition#isReadOnly()},
 *       {@code httpMethod=GET}): {@code GET /api/inbound/{product}/{version}} — sin
 *       {@code requestBody}, sin cabecera {@code X-Idempotency-Key} y sin respuesta 400/409
 *       (catálogos sin payload de entrada).</li>
 * </ul>
 *
 * <p>Mapeo de tipos {@link FieldType} a OpenAPI:
 * <ul>
 *   <li>{@code STRING}   → {@code type: string}, {@code maxLength} cuando esté definido</li>
 *   <li>{@code INTEGER}  → {@code type: integer, format: int64}</li>
 *   <li>{@code BOOLEAN}  → {@code type: boolean}</li>
 *   <li>{@code DATETIME} → {@code type: string, format: date-time}</li>
 *   <li>{@code ARRAY}    → {@code type: array} de items {@code string}</li>
 * </ul>
 *
 * <p>Los schemas de respuesta ({@code ApiResponse} y {@code ApiError}) se declaran
 * una sola vez en {@code components/schemas} y las operaciones los referencian con
 * {@code $ref} para no duplicar.
 *
 * <p>Cabeceras documentadas: {@code X-Correlation-ID} (opcional, todas las operaciones)
 * y {@code X-Idempotency-Key} (requerida, solo POST/PATCH — GET es idempotente por
 * naturaleza y no la exige).
 */
@Slf4j
@RequiredArgsConstructor
public class InboundContractOpenApiCustomizer implements OpenApiCustomizer {

    private static final String EDITAR_SUFFIX  = "_EDITAR";
    private static final String MEDIA_TYPE_JSON = "application/json";

    /** Nombre del schema componente de respuesta exitosa/error reutilizable. */
    static final String SCHEMA_API_RESPONSE = "ApiResponse";
    static final String SCHEMA_API_ERROR    = "ApiError";
    static final String SCHEMA_API_VIOLATION = "ApiViolation";

    private final ContractRegistry contractRegistry;

    @Override
    public void customise(OpenAPI openApi) {
        asegurarInfo(openApi);
        registrarSchemasComponente(openApi);
        registrarPaths(openApi);
        log.info("OpenAPI inbound customizer: paths generados desde ContractRegistry");
    }

    // ─── Info ─────────────────────────────────────────────────────────────────

    private static void asegurarInfo(OpenAPI openApi) {
        openApi.info(new Info()
                .title("Hub de Interoperabilidad — APIs de productos")
                .version("1.0")
                .description("""
                        Catálogo de APIs inbound del hub de interoperabilidad.

                        **Este catálogo se genera automáticamente** desde la configuración
                        declarativa (`hub.apis`): cada bloque YAML que agrega una API nueva
                        aparece aquí sin cambios de código.

                        ## Seguridad

                        La seguridad (mTLS + OAuth2 Bearer enlazado al certificado, RFC 8705)
                        la aplica el **API Gateway** antes de que la petición llegue a este
                        servicio. Los partners obtienen su token en
                        `POST /oauth2/token` (gateway) con `grant_type=client_credentials`.

                        ## Cabeceras comunes (operaciones de escritura)

                        | Cabecera | Obligatoria | Descripción |
                        |---|---|---|
                        | `X-Idempotency-Key` | Sí | Clave única de la operación; reenvíos con la misma clave son idempotentes |
                        | `X-Correlation-ID`  | No | ID de correlación propagado en la respuesta para trazabilidad distribuida |
                        | `X-Partner-Id`      | No | Identificador del partner; lo inyecta el gateway automáticamente |

                        ## Ruta equivalente vía gateway (partners)

                        Las APIs internas están bajo `/api/inbound/…`; el gateway las expone
                        a los partners en `/partner/v1/inbound/…`.
                        """));
    }

    // ─── Schemas componente ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void registrarSchemasComponente(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.components(new Components());
        }
        Components components = openApi.getComponents();

        // ApiViolation
        if (!schemas(components).containsKey(SCHEMA_API_VIOLATION)) {
            Schema<Object> violacion = new ObjectSchema()
                    .description("Violación de contrato de un campo individual")
                    .addProperty("field",   new Schema<String>().type("string")
                            .description("Nombre del campo que incumple la regla"))
                    .addProperty("message", new Schema<String>().type("string")
                            .description("Descripción legible del incumplimiento"));
            components.addSchemas(SCHEMA_API_VIOLATION, violacion);
        }

        // ApiError
        if (!schemas(components).containsKey(SCHEMA_API_ERROR)) {
            ArraySchema violaciones = new ArraySchema()
                    .items(new Schema<>().$ref("#/components/schemas/" + SCHEMA_API_VIOLATION));
            Schema<Object> error = new ObjectSchema()
                    .description("Detalle estructurado del error")
                    .addProperty("code",       new Schema<String>().type("string")
                            .description("Código de error máquina (ej. VALIDATION_ERROR)"))
                    .addProperty("detail",     new Schema<String>().type("string")
                            .description("Descripción legible del error"))
                    .addProperty("violations", violaciones
                            .description("Lista de violaciones de contrato; vacía si el error no es de validación"));
            components.addSchemas(SCHEMA_API_ERROR, error);
        }

        // ApiResponse
        if (!schemas(components).containsKey(SCHEMA_API_RESPONSE)) {
            Schema<Object> respuesta = new ObjectSchema()
                    .description("Sobre genérico de respuesta HTTP del hub (ADR-0005)")
                    .addProperty("success",       new Schema<Boolean>().type("boolean")
                            .description("true si la operación fue exitosa"))
                    .addProperty("status",        new Schema<Integer>().type("integer")
                            .description("Código HTTP del resultado"))
                    .addProperty("message",       new Schema<String>().type("string")
                            .description("Mensaje legible resumiendo el resultado"))
                    .addProperty("data",          new ObjectSchema()
                            .description("Datos del negocio; null en respuestas de error"))
                    .addProperty("error",         new Schema<>()
                            .$ref("#/components/schemas/" + SCHEMA_API_ERROR)
                            .description("Detalle del error; null en respuestas exitosas"))
                    .addProperty("correlationId", new Schema<String>().type("string")
                            .description("ID de correlación para trazabilidad distribuida"))
                    .addProperty("timestamp",     new Schema<String>().type("string")
                            .format("date-time")
                            .description("Instante ISO 8601 con offset de la respuesta"));
            components.addSchemas(SCHEMA_API_RESPONSE, respuesta);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Schema> schemas(Components components) {
        if (components.getSchemas() == null) {
            components.setSchemas(new LinkedHashMap<>());
        }
        return components.getSchemas();
    }

    // ─── Paths ────────────────────────────────────────────────────────────────

    private void registrarPaths(OpenAPI openApi) {
        Collection<ContractDefinition> contratos = contractRegistry.allContracts();
        if (contratos.isEmpty()) {
            log.warn("ContractRegistry vacío — el customizer OpenAPI no generará paths inbound");
            return;
        }

        if (openApi.getPaths() == null) {
            openApi.paths(new io.swagger.v3.oas.models.Paths());
        }

        contratos.forEach(contrato -> generarPath(openApi, contrato));
    }

    private void generarPath(OpenAPI openApi, ContractDefinition contrato) {
        String producto = contrato.product();
        String version  = contrato.version();
        boolean esEdicion = producto.endsWith(EDITAR_SUFFIX);

        if (esEdicion) {
            String productoBase = producto.substring(0, producto.length() - EDITAR_SUFFIX.length());
            String path = "/api/inbound/" + productoBase + "/" + version + "/{id}";
            registrarPathItem(openApi, path, contrato, productoBase, Verbo.PATCH);
        } else {
            String path = "/api/inbound/" + producto + "/" + version;
            Verbo verbo = contrato.isReadOnly() ? Verbo.GET : Verbo.POST;
            registrarPathItem(openApi, path, contrato, producto, verbo);
        }
    }

    private void registrarPathItem(OpenAPI openApi,
                                   String path,
                                   ContractDefinition contrato,
                                   String productoBase,
                                   Verbo verbo) {
        PathItem pathItem = openApi.getPaths()
                .computeIfAbsent(path, k -> new PathItem());

        Operation operacion = construirOperacion(contrato, productoBase, verbo);

        switch (verbo) {
            case PATCH -> pathItem.patch(operacion);
            case GET   -> pathItem.get(operacion);
            case POST  -> pathItem.post(operacion);
        }

        log.debug("Path OpenAPI generado: {} {} ({})", verbo, path, contrato.product());
    }

    // ─── Construcción de operación ────────────────────────────────────────────

    /** Verbo HTTP de la operación generada — determina forma de request/response documentada. */
    private enum Verbo { POST, PATCH, GET }

    private Operation construirOperacion(ContractDefinition contrato,
                                          String productoBase,
                                          Verbo verbo) {
        String version = contrato.version();

        Operation op = new Operation()
                .operationId(verbo.name().toLowerCase() + "_" + productoBase + "_" + version)
                .summary(resumenPorVerbo(verbo) + " " + productoBase.replace("_", " ").toLowerCase())
                .description(construirDescripcion(productoBase, version, verbo))
                .addTagsItem(productoBase)
                .parameters(construirParametros(contrato, verbo))
                .responses(construirResponses(verbo));

        // GET no tiene body de entrada — catálogos de solo lectura sin payload.
        if (verbo != Verbo.GET) {
            op.requestBody(construirRequestBody(contrato, productoBase, verbo));
        }

        return op;
    }

    private static String resumenPorVerbo(Verbo verbo) {
        return switch (verbo) {
            case POST -> "Crear";
            case PATCH -> "Editar";
            case GET -> "Consultar";
        };
    }

    private static String construirDescripcion(String productoBase, String version, Verbo verbo) {
        String rutaPartner = "/partner/v1/inbound/" + productoBase + "/" + version
                + (verbo == Verbo.PATCH ? "/{id}" : "");

        String tipoOperacion = switch (verbo) {
            case POST -> "Creación de recurso";
            case PATCH -> "Edición de recurso";
            case GET -> "Consulta de catálogo (solo lectura, sin payload de entrada)";
        };

        return String.format("""
                %s del producto **%s** (`%s`).

                **Ruta partner equivalente vía gateway:** `%s %s`

                El catálogo de campos y sus reglas de validación proviene directamente de
                `hub.apis` — no requiere cambios de código al añadir o modificar campos.
                """,
                tipoOperacion,
                productoBase,
                verbo.name(),
                verbo.name(),
                rutaPartner);
    }

    private static List<Parameter> construirParametros(ContractDefinition contrato, Verbo verbo) {
        List<Parameter> params = new ArrayList<>();

        // Cabecera X-Idempotency-Key — requerida solo en operaciones de escritura.
        // GET es idempotente por naturaleza (ver HubAuditInterceptor) y no la exige.
        if (verbo != Verbo.GET) {
            params.add(new Parameter()
                    .name("X-Idempotency-Key")
                    .in("header")
                    .required(true)
                    .description("Clave única de la operación. Reenvíos con la misma clave son " +
                                 "idempotentes y no generan duplicados.")
                    .schema(new Schema<String>().type("string")));
        }

        // Cabecera X-Correlation-ID — opcional, para todas las operaciones
        params.add(new Parameter()
                .name("X-Correlation-ID")
                .in("header")
                .required(false)
                .description("ID de correlación para trazabilidad distribuida. " +
                             "Si se omite, el servicio genera uno automáticamente y lo devuelve " +
                             "en la cabecera de respuesta.")
                .schema(new Schema<String>().type("string")));

        // Parámetro de path {id} para operaciones PATCH
        if (verbo == Verbo.PATCH) {
            String campoId = contrato.resourceIdField() != null
                    ? contrato.resourceIdField()
                    : "id";
            params.add(new Parameter()
                    .name("id")
                    .in("path")
                    .required(true)
                    .description("Identificador numérico del recurso a editar. " +
                                 "Se inyecta automáticamente en el payload bajo el campo `" +
                                 campoId + "`.")
                    .schema(new Schema<Long>().type("integer").format("int64")));
        }

        return params;
    }

    private static RequestBody construirRequestBody(ContractDefinition contrato,
                                                     String productoBase,
                                                     Verbo verbo) {
        Schema<Object> schema = construirSchema(contrato, productoBase, verbo);

        return new RequestBody()
                .required(true)
                .description("Payload del contrato `" + contrato.product() + "/" +
                             contrato.version() + "`. " +
                             "Los campos marcados como requeridos son obligatorios.")
                .content(new Content()
                        .addMediaType(MEDIA_TYPE_JSON,
                                new MediaType().schema(schema)));
    }

    @SuppressWarnings("unchecked")
    private static Schema<Object> construirSchema(ContractDefinition contrato,
                                                   String productoBase,
                                                   Verbo verbo) {
        ObjectSchema schema = new ObjectSchema();
        schema.name(productoBase + (verbo == Verbo.PATCH ? "_Editar" : "_Crear") + "_" + contrato.version());
        schema.description("Payload del contrato " + contrato.product() + "/" + contrato.version());

        List<String> requeridos = new ArrayList<>();

        for (FieldRule regla : contrato.fields()) {
            Schema<?> propSchema = schemaParaTipo(regla);
            schema.addProperty(regla.field(), propSchema);
            if (regla.required()) {
                requeridos.add(regla.field());
            }
        }

        if (!requeridos.isEmpty()) {
            schema.required(requeridos);
        }

        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Schema<?> schemaParaTipo(FieldRule regla) {
        return switch (regla.type()) {
            case STRING -> {
                Schema<String> s = new Schema<String>().type("string");
                if (regla.maxLength() != null) {
                    s.maxLength(regla.maxLength());
                }
                yield s;
            }
            case INTEGER -> new Schema<Long>().type("integer").format("int64");
            case BOOLEAN -> new Schema<Boolean>().type("boolean");
            case DATETIME -> new Schema<String>().type("string").format("date-time")
                    .description("Fecha/hora ISO 8601 con offset (ej. 2025-01-15T10:30:00-04:00)");
            case ARRAY -> new ArraySchema()
                    .items(new Schema<String>().type("string"))
                    .description("Arreglo de valores");
        };
    }

    // ─── Responses ────────────────────────────────────────────────────────────

    private static ApiResponses construirResponses(Verbo verbo) {
        Schema<?> refApiResponse = new Schema<>().$ref("#/components/schemas/" + SCHEMA_API_RESPONSE);

        ApiResponses responses = new ApiResponses();

        // Éxito
        String codigoExito = verbo == Verbo.POST ? "201" : "200";
        String descripcionExito = switch (verbo) {
            case POST -> "Recurso creado correctamente";
            case PATCH -> "Recurso editado correctamente";
            case GET -> "Catálogo obtenido correctamente";
        };
        responses.addApiResponse(codigoExito, new ApiResponse()
                .description(descripcionExito)
                .content(new Content().addMediaType(MEDIA_TYPE_JSON,
                        new MediaType().schema(refApiResponse))));

        // 400 Validación — no aplica a GET (sin body de entrada que pueda incumplir el contrato)
        if (verbo != Verbo.GET) {
            responses.addApiResponse("400", new ApiResponse()
                    .description("Payload inválido — el contrato no se cumple " +
                                 "(campos requeridos ausentes, tipos incorrectos, maxLength excedido)")
                    .content(new Content().addMediaType(MEDIA_TYPE_JSON,
                            new MediaType().schema(new Schema<>()
                                    .$ref("#/components/schemas/" + SCHEMA_API_RESPONSE)))));
        }

        // 403 Producto no autorizado
        responses.addApiResponse("403", new ApiResponse()
                .description("Producto no autorizado o no registrado en el hub")
                .content(new Content().addMediaType(MEDIA_TYPE_JSON,
                        new MediaType().schema(new Schema<>()
                                .$ref("#/components/schemas/" + SCHEMA_API_RESPONSE)))));

        // 409 Idempotencia — solo aplica a operaciones de escritura
        if (verbo != Verbo.GET) {
            responses.addApiResponse("409", new ApiResponse()
                    .description("X-Idempotency-Key ya procesada — operación duplicada rechazada")
                    .content(new Content().addMediaType(MEDIA_TYPE_JSON,
                            new MediaType().schema(new Schema<>()
                                    .$ref("#/components/schemas/" + SCHEMA_API_RESPONSE)))));
        }

        // 500 Error interno
        responses.addApiResponse("500", new ApiResponse()
                .description("Error interno del hub — contactar al equipo de operaciones")
                .content(new Content().addMediaType(MEDIA_TYPE_JSON,
                        new MediaType().schema(new Schema<>()
                                .$ref("#/components/schemas/" + SCHEMA_API_RESPONSE)))));

        return responses;
    }
}
