# Ficha Técnica — Interoperabilidad Ministerio Público y Policía (FELCN)

**Ministerio Público — Fiscalía General del Estado, Bolivia**
**Seguridad de la Información — Área de Desarrollo de Sistemas**
**Versión 1.0 — 2024**

---

## Tabla de contenido

- [1. Introducción](#1-introducción)
  - [1.1. Estructura de información](#11-estructura-de-información)
  - [1.2. Autenticación y Autorización](#12-autenticación-y-autorización)
- [2. Envío de información de Policía al Ministerio Público](#2-envío-de-información-de-policía-al-ministerio-público)
- [3. Envío de información de Ministerio Público a Policía](#3-envío-de-información-de-ministerio-público-a-policía)

---

## 1. Introducción

### 1.1. Estructura de información

#### Catálogos

El Ministerio Público ha centralizado la gestión de sus datos maestros en una plataforma de catálogos ubicada en **https://catalogos.mp.gob.bo/**. Esta plataforma sirve como un repositorio único para todos los catálogos utilizados en las diferentes áreas de la institución.

Gracias a esta centralización, la interoperabilidad con otras instituciones se simplifica significativamente. Al utilizar únicamente los identificadores (IDs) de los catálogos en los intercambios de información, se garantiza la consistencia e integridad de los datos compartidos, evitando duplicaciones y errores.

#### Estructura de respuestas

Esta estructura permite saber si la solicitud fue exitosa, obtener un mensaje descriptivo y acceder a los datos específicos que se solicitó. Es una forma estándar de comunicar información en consumo de servicios.

```json
{
  "error": false,
  "message": "Lista memoriales",
  "response": { "data": [{}, {}] },
  "status": 200
}
```

#### Estructura de errores

Cuando se produce un error durante el procesamiento de una solicitud, la API devuelve una respuesta con esta estructura para informar al cliente sobre el problema específico. Esto permite al cliente identificar los campos que necesitan ser corregidos y enviar una nueva solicitud válida.

```json
{
  "error": true,
  "message": "Error de validación",
  "response": {
    "validationErrors": {
      "field": [
        "error validation 1",
        "error validation 2"
      ]
    }
  },
  "status": 400
}
```

### 1.2. Autenticación y Autorización

Para acceder a los servicios ofrecidos por el Ministerio Público, todas las entidades externas deberán autenticarse a través de un mecanismo basado en tokens.

Inicialmente, se establece un **token padre** con una vigencia igual a la duración del convenio.

Para obtener el **token hijo** se consume el endpoint `/login`, enviando el token padre junto con información del usuario que realiza la solicitud.

Como respuesta, se obtiene un token hijo con una vigencia de una hora. Este token hijo es el que se utilizará para autenticar las subsiguientes solicitudes a los servicios del modelo de negocios acordado, como por ejemplo, la consulta de alertas migratorias.

#### Request

```
POST {urlBase}/login
Header - Authorization: token padre vigente
```

```json
{
  "ci": "123456789",
  "nombres": "Pepito",
  "primerApellido": "Perez",
  "segundoApellido": "Lopez",
  "fechaNacimiento": "2001-12-25T00:00:00.000Z",
  "identificador": "MG"
}
```

Si los datos son correctos, el servicio retornará el token hijo.

#### Response

```json
{
  "error": false,
  "message": "Inicio válido",
  "response": {
    "data": {
      "token": "eyJhbGciO...uW0sYAI4",
      "tokenVigencia": "2024-11-22T17:51:40.000Z"
    }
  },
  "status": 200
}
```

Con el cual se podrá consumir los demás servicios ya del modelo de negocios.

---

## 2. Envío de información de Policía al Ministerio Público

> **C**: Crear · **E**: Editar
> Los servicios se consumen con el token hijo.

Flujo: **POL → MP**
Caso (CE) → Delitos Inicial (CE) → Sujetos (CE) → Situación jurídica sujeto (C) → Domicilios sujeto (C) → Abogados sujeto (CE) → Investigadores (CE) → Actividades (C) → Acto investigativo (E) → Reserva (caso, sujeto, actividad).

### 2.1. Caso: Crear

```
POST {urlMP}/caso
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| cud | string | requerido | Código único de denuncia |
| casoIdExterno | int | requerido | Primary key de MP |
| casoPadreIdExterno | int | | |
| tipoDenunciaId | int | requerido | |
| creacionFechaHora | date | requerido | |
| estaReservado | bool | | |
| oficinaComunId | int | requerido | |
| oficinaIdExterno | int | requerido | |
| hechoMunicipioId | int | | |
| hechoZona | string | | |
| hechoDireccion | string | | |
| hechoLatitud | string | | |
| hechoLongitud | string | | |
| hechoReferenciaLugar | string | | |
| relatos | JSON | | Objeto con el relato del hecho |
| hechoFechaHora | date | | |
| hechoFechaHoraFin | date | | |
| hechoFechaHoraAproximada | string | | |
| casoEstadoId | int | requerido | |
| casoEtapaId | int | requerido | |
| denominacionCaso | string | | Solo si es mediático de la denuncia |
| tags | array | | `['misiles','china']` |

**RELATO**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| quienHizo | string | | relato quien hizo |
| queHizo | string | | relato que hizo |
| comoHizo | string | | relato como hizo |
| resultado | string | | relato resultado |
| hechoRelato | string | | relato hechoRelato |

**Respuesta:**

```json
{
  "error": false,
  "message": "Caso registrado correctamente",
  "response": { "mpCasoId": 1 },
  "status": 200
}
```

### 2.2. Caso: Editar

```
PATCH {urlMP}/caso/{mpCasoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPadreId | int | | |
| tipoDenunciaId | int | requerido | |
| hechoMunicipioId | int | | |
| hechoZona | string | | |
| hechoDireccion | string | | |
| hechoLatitud | string | | |
| hechoLongitud | string | | |
| hechoReferenciaLugar | string | | |
| hechoRelato | text | | |
| hechoFechaHora | date | | |
| hechoFechaHoraFin | date | | |
| hechoFechaHoraAproximada | string | | |
| denominacionCaso | string | | Solo si es mediático de la denuncia |
| tagsId | array | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Caso registrado correctamente",
  "response": {},
  "status": 200
}
```

### 2.3. Delito inicial del caso: Crear

```
POST {urlMP}/caso/delitos
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoId | int | requerido | Primary Key de OJ |
| delitos | array | requerido | Array de objetos de delitos del caso |

**Objeto: Delito de caso**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoDelitoId | int | requerido | Primary Key MP |
| delitoId | int | requerido | Id del delito en el Catálogo |

**Respuesta:**

```json
{
  "error": false,
  "message": "Delitos registrados correctamente",
  "response": {
    "delitos": [{ "polCasoDelitoId": 1, "mpCasoDelitoId": 1 }]
  },
  "status": 200
}
```

### 2.4. Delitos del caso: Editar

```
PATCH {urlMP}/caso/delitos/{mpCasoDelitoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | tinyint | requerido | Para dar de baja el delito del caso |

**Respuesta:**

```json
{
  "error": false,
  "message": "Delito actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 2.5. Sujetos del caso: Crear

```
POST {urlMP}/caso/sujetos
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoId | int | requerido | |
| sujetos | array | requerido | Lista de objetos de sujetos |

**Objeto sujetos**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| extCasoPersonaId | int | requerido | |
| tipoSujetoId | array | requerido | Lista de objetos de sujetos |
| parentescoVictimaId | int | | |
| personaNatural | objeto | sí y no | Objeto persona natural, requerido si no hay persona jurídica |
| personaJuridica | objeto | sí y no | Objeto persona jurídica, requerido si no hay persona natural |
| reservaIdentidad | bool | | |
| esQuerellante | bool | | |

**Objeto personas naturales**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoIdentidadId | int | requerido | Con segip, Sin segip, NN, Contra-autores |
| tipoDocumentoId | int | requerido | |
| numeroDocumento | string | requerido | |
| nombres | string | | |
| primerApellido | string | sí y no | Algunas personas no tienen primer apellido |
| segundoApellido | string | sí y no | Algunas personas no tienen segundo apellido |
| fechaNacimiento | date | | |
| sexo | bool | | |
| generoId | int | | |
| autoidentificacionId | int | | |
| estadoCivilId | int | | |
| idiomasId | array | | |
| profesion | string | | |
| celular | string | | |
| celularPaisId | int | | |
| email | string | | |
| nivelEducacionId | int | | |
| grupoVulnerableId | int | | |
| gradoDiscapacidadId | int | | |
| nacionalidadId | int | | |
| nacimientoMunicipioId | int | | |
| esCiudadanoDigital | bool | | |
| estaFallecido | bool | | |
| estaDesaparecido | bool | | |

**Objeto personas jurídicas**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| razonSocial | string | requerido | |
| nit | string | requerido | |
| telefono | string | | |
| email | string | | |
| latitud | string | | |
| longitud | string | | |
| municipioId | int | requerido | |
| direccion | string | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Sujetos registrados correctamente",
  "response": [{ "polCasoPersonaId": 1, "mpCasoPersonaId": 1 }],
  "status": 200
}
```

### 2.6. Sujetos del caso: Editar

```
PATCH {urlMP}/caso/sujetos/{mpCasoPersonaId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSujetoId | int | requerido | |
| parentescoVictimaId | int | | |
| personaNatural | objeto | sí y no | Requerido solo si persona jurídica es null |
| personaJuridica | objeto | sí y no | Requerido solo si persona natural es null |
| esQuerellante | bool | | |
| estado | tinyint | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Sujeto actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 2.7. Abogados del sujeto: Crear

```
POST {urlMP}/caso/sujetos/abogados
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaId | int | requerido | |
| abogados | array | requerido | Lista de objetos de abogados |

**Objeto abogado**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaAbogadoId | int | requerido | |
| codigoRPA | string | requerido | |
| ci | string | requerido | |
| nombres | string | requerido | |
| primerApellido | string | | |
| segundoApellido | string | | |
| fechaNacimiento | date | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Abogados registrados correctamente",
  "response": {
    "situacionJuridicas": [
      { "polCasoPersonaAbogadoId": 1, "mpCasoPersonaAbogadoId": 1 }
    ]
  },
  "status": 200
}
```

### 2.8. Abogados del sujeto: Editar

```
PATCH {urlMP}/caso/sujetos/abogados/{mpCasoPersonaAbogadoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | tinyint | requerido | Para dar de baja al abogado |
| motivoBaja | string | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Abogados actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 2.9. Situaciones jurídicas del sujeto: Crear

```
POST {urlMP}/caso/sujetos/situacion-juridicas
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaId | int | requerido | |
| situacionesJuridicas | array | requerido | Lista de objetos de situaciones jurídicas |

**Objeto situación jurídica**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaSituacionJuridicaId | int | requerido | |
| situacionJuridicaId | int | requerido | Catálogo de situaciones jurídicas |
| fechaInicio | date | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Situación jurídica registrados correctamente",
  "response": {
    "situacionJuridicas": [
      { "polCasoPersonaSituacionJuridicaId": 1, "mpCasoPersonaSituacionJuridicaId": 1 }
    ]
  },
  "status": 200
}
```

### 2.10. Domicilios del sujeto: Crear

```
POST {urlMP}/caso/sujetos/domicilios
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polPersonaDomicilioId | int | requerido | Enviado para mantener integridad de información; con lo cual OJ también responderá con qué id se ha almacenado en su BD (ojPersonaResidenciaId) |
| mpCasoPersonaId | int | requerido | |
| paisId | int | requerido | |
| municipioId | int | | |
| direccion | string | | |
| latitud | string | | |
| longitud | string | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Residencias registrado correctamente",
  "response": { "mpPersonaResidenciaId": 1 },
  "status": 200
}
```

### 2.11. Investigadores del caso: Crear

```
POST {urlMP}/caso/investigadores
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoId | int | requerido | |
| investigadores | array | requerido | Lista de objetos de investigadores |

**Objeto Investigador**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoFuncionarioId | int | requerido | |
| tipoResponsableId | int | requerido | Catálogo de ministerio público |
| ci | string | requerido | |
| nombres | string | requerido | |
| primerApellido | string | | |
| segundoApellido | string | | |
| fechaNacimiento | date | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Investigador registrados correctamente",
  "response": [{ "polCasoFuncionarioId": 1, "mpCasoFuncionarioId": 1 }],
  "status": 200
}
```

### 2.12. Investigadores del caso: Editar

```
PATCH {urlMP}/caso/investigadores/{mpCasoFuncionarioId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | tinyint | requerido | Para dar de baja investigador |

**Respuesta:**

```json
{
  "error": false,
  "message": "Investigador actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 2.13. Actividades del caso: Crear

```
POST {urlMP}/caso/actividades
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoId | int | requerido | |
| actividades | array | requerido | Lista de objetos de actividades |

**Objeto actividad**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| extCasoActividadId | int | requerido | |
| respuestaOjCasoActividadId | int | | |
| actividadId | int | requerido | |
| referencia | string | requerido | |
| archivoExtension | string | | Ejemplo: pdf, mp3, mp4 |
| archivoLink | string | | |
| archivoTamano | int | | Tamaño del fichero en bytes |
| archivoPaginas | int | | Cantidad de páginas del documento |
| archivoHash | string | requerido | |
| aprobacionesCD | array | | Lista de objetos usuario |
| firmasDigitales | array | | Lista de objetos usuario |
| metaData | objeto | | Objeto metadata |

**1. Objeto metadata (Solicitud detención preventiva y ampliación detención preventiva)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| sujetos | array | requerido | Lista de objetos de detención preventiva |

*Objeto detención preventiva*

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaId | int | requerido | |
| tiempoDias | int | requerido | Tiempo solicitado |
| riesgosProcesalesId | array | | Lista de id's de riesgos procesales |

**2. Objeto metadata (Solicitud sentencia condenatoria)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| sujetos | array | requerido | Lista de objetos sentencia condenatoria |

*Objeto solicitud sentencia condenatoria*

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaId | int | requerido | |
| tiempoPenaDias | int | requerido | Tiempo solicitado privación de libertad |
| tiempoMultaDias | int | | Económico |
| tiempoTrabajoDias | int | | Trabajo comunitario |

**3. Objeto metadata (Solicitud otorgamiento de medidas de protección)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| sujetos | array | requerido | Lista de objetos de medidas de protección |

*Objeto de medidas de protección*

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaId | int | requerido | |
| medidasProteccionId | array | requerido | Lista de id's de medidas de protección |

**4. Objeto metadata (Solicitud ampliar plazos procesales)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| tiempoDias | int | requerido | Tiempo solicitado para ampliación |

**5. Objeto metadata (Solicitud paralizar plazos procesales)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |

**6. Objeto metadata (Solicitud dar de baja actividad)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| referenciaOjCasoActividadId | int | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Actividades registrados correctamente",
  "response": [{ "polCasoActividadId": 1, "mpCasoActividadId": 1 }],
  "status": 200
}
```

### 2.18. Reserva de: caso, sujeto, actividad

> `tabla`: 1 = caso, 2 = sujeto, 3 = actividades
> `tablaId`: mpCasoId, mpCasoPersonaId, mpCasoActividadId

```
POST {urlMP}/set/reserva
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | int | requerido | |
| tabla | int | requerido | |
| tablaId | int | requerido | |
| fechaFinReserva | date | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Reserva registrado correctamente",
  "response": null,
  "status": 200
}
```

### 2.19. Agenda de audiencias: Editar

```
PATCH {urlMP}/caso/agenda/{mpAgendaId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoEventoId | int | requerido | |
| tipoSubEventoId | int | | |
| juzgadoId | int | requerido | |
| titulo | string | requerido | |
| descripcion | string | requerido | |
| estado | tinyint | | |
| latitud | string | | |
| longitud | string | | |
| fechaInicio | date | requerido | |
| fechaFin | date | requerido | |
| esVirtual | bool | | |
| direccion | string | | |
| linkReunion | string | | |
| conclusionId | int | | |
| mpCasoPersonaIds | array | | |
| mpCasoFuncionariosIds | array | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Agenda actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 2.20. Descargar Archivo desde URL

Servicio para descargar el archivo (en base 64 u otro) para tenerlo en nuestro sistema.

```
GET {urlPOL}/{archivoHash}?user=usuarioCi
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| archivoHash | string | requerido | |
| usuarioCi | string | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "documento obtenido",
  "response": { "data": "BASE64aasfddddgfgfffggrrrrrdfddfdsf…" },
  "status": 200
}
```

### 2.21. Oficinas de Policía

Servicio para obtener las oficinas de la policía.

```
GET {urlPOL}/oficinas/{oficinaId}?user=usuarioCi
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| oficinaId | number | requerido | |
| usuarioCi | string | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "documento obtenido",
  "response": {
    "data": {
      "id": 1,
      "nombre": "oficina nombre",
      "municipioId": 1,
      "latitud": "-19.22564",
      "longitud": "-23.3355447",
      "tipoComandoPolicialId": 1,
      "identificador": "PLAT",
      "direccion": "calle policia",
      "telefono": "6420638"
    }
  },
  "status": 200
}
```

**Parámetros de respuesta**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| id | number | requerido | |
| nombre | string | requerido | |
| municipioId | number | requerido | |
| latitud | string | requerido | |
| longitud | string | requerido | |
| tipoComandoPolicialId | number | requerido | |
| identificador | string | opcional | |
| direccion | string | opcional | |
| telefono | string | opcional | |

---

## 3. Envío de información de Ministerio Público a Policía

> **C**: Crear · **E**: Editar
> Los servicios se consumen con el token hijo.

Flujo: **MP → POL**
Caso (CE) → Delitos Inicial (CE) → Delitos principal (CE) → Juzgado (CE) → Sujetos (CE) → Situación jurídica sujeto (C) → Domicilios sujeto (C) → Abogados sujeto (CE) → Fiscales (CE) → Actividades (C) → Acto investigativo (E) → Reserva (caso, sujeto, actividad).

### 3.1. Caso: Crear

```
POST {urlPOL}/caso
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| cud | string | requerido | Código único de denuncia |
| mpCasoId | int | requerido | Primary key de MP |
| mpCasoPadreId | int | | |
| tipoDenunciaId | int | requerido | |
| creacionFechaHora | date | requerido | |
| estaReservado | bool | | |
| oficinaComunId | int | requerido | |
| hechoMunicipioId | int | | |
| hechoZona | string | | |
| hechoDireccion | string | | |
| hechoLatitud | string | | |
| hechoLongitud | string | | |
| hechoReferenciaLugar | string | | |
| hechoRelato | text | | |
| hechoFechaHora | date | | |
| hechoFechaHoraFin | date | | |
| hechoFechaHoraAproximada | string | | |
| casoEstadoId | int | requerido | |
| casoEtapaId | int | requerido | |
| denominacionCaso | string | | Solo si es mediático de la denuncia |
| tags | array | | `['misiles','china']` |

**Respuesta:**

```json
{
  "error": false,
  "message": "Caso registrado correctamente",
  "response": { "polCasoId": 1 },
  "status": 200
}
```

### 3.2. Caso: Editar

```
PATCH {urlPOL}/caso/{polCasoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPadreId | int | | |
| tipoDenunciaId | int | requerido | |
| hechoMunicipioId | int | | |
| hechoZona | string | | |
| hechoDireccion | string | | |
| hechoLatitud | string | | |
| hechoLongitud | string | | |
| hechoReferenciaLugar | string | | |
| hechoRelato | text | | |
| hechoFechaHora | date | | |
| hechoFechaHoraFin | date | | |
| hechoFechaHoraAproximada | string | | |
| denominacionCaso | string | | Solo si es mediático de la denuncia |
| tagsId | array | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Caso registrado correctamente",
  "response": {},
  "status": 200
}
```

### 3.3. Delito inicial del caso: Crear

```
POST {urlPOL}/caso/delitos
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | Primary Key de OJ |
| delitos | array | requerido | Array de objetos de delitos del caso |

**Objeto: Delito de caso**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoDelitoId | int | requerido | Primary Key MP |
| delitoId | int | requerido | Id del delito en el Catálogo |

**Respuesta:**

```json
{
  "error": false,
  "message": "Delitos inicial registrados correctamente",
  "response": {
    "delitos": [{ "mpCasoDelitoId": 1, "polCasoDelitoId": 1 }]
  },
  "status": 200
}
```

### 3.4. Delitos inicial del caso: Editar

```
PATCH {urlPOL}/caso/delitos/{polCasoDelitoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | tinyint | requerido | Para dar de baja el delito del caso |

**Respuesta:**

```json
{
  "error": false,
  "message": "Delito actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.5. Delito principal del caso: Crear

```
POST {urlPOL}/caso/delitos
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | Primary Key de OJ |
| delitos | array | requerido | Array de objetos de delitos del caso |

**Objeto: Delito de caso**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoDelitoId | int | requerido | Primary Key MP |
| delitoId | int | requerido | Id del delito en el Catálogo |
| esPrincipal | bool | requerido | |
| esTentativo | bool | requerido | Tentativo / Consumado |

**Respuesta:**

```json
{
  "error": false,
  "message": "Delitos principal registrados correctamente",
  "response": {
    "delitos": [{ "mpCasoDelitoId": 1, "polCasoDelitoId": 1 }]
  },
  "status": 200
}
```

### 3.6. Delitos del caso: Editar

```
PATCH {urlPOL}/caso/delitos/{polCasoDelitoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| esPrincipal | bool | requerido | |
| esTentativo | bool | requerido | |
| estado | tinyint | requerido | Para dar de baja el delito del caso |

**Respuesta:**

```json
{
  "error": false,
  "message": "Delito principal actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.7. Sujetos del caso: Crear

```
POST {urlPOL}/caso/sujetos
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | |
| sujetos | array | requerido | Lista de objetos de sujetos |

**Objeto sujetos**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaId | int | requerido | |
| tipoSujetoId | array | requerido | Lista de objetos de sujetos |
| parentescoVictimaId | int | | |
| personaNatural | objeto | sí y no | Objeto persona natural, requerido si no hay persona jurídica |
| personaJuridica | objeto | sí y no | Objeto persona jurídica, requerido si no hay persona natural |
| reservaIdentidad | bool | | |
| esQuerellante | bool | | |

**Objeto personas naturales**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoIdentidadId | int | requerido | Con segip, Sin segip, NN, Contra-autores |
| tipoDocumentoId | int | requerido | |
| numeroDocumento | string | requerido | |
| nombres | string | | |
| primerApellido | string | sí y no | Algunas personas no tienen primer apellido |
| segundoApellido | string | sí y no | Algunas personas no tienen segundo apellido |
| fechaNacimiento | date | | |
| sexo | bool | | |
| generoId | int | | |
| autoidentificacionId | int | | |
| estadoCivilId | int | | |
| idiomasId | array | | |
| profesion | string | | |
| celular | string | | |
| celularPaisId | int | | |
| email | string | | |
| nivelEducacionId | int | | |
| grupoVulnerableId | int | | |
| gradoDiscapacidadId | int | | |
| nacionalidadId | int | | |
| nacimientoMunicipioId | int | | |
| esCiudadanoDigital | bool | | |
| estaFallecido | bool | | |
| estaDesaparecido | bool | | |

**Objeto personas jurídicas**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| razonSocial | string | requerido | |
| nit | string | requerido | |
| telefono | string | | |
| email | string | | |
| latitud | string | | |
| longitud | string | | |
| municipioId | int | requerido | |
| direccion | string | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Sujetos registrados correctamente",
  "response": [{ "mpCasoPersonaId": 1, "polCasoPersonaId": 1 }],
  "status": 200
}
```

### 3.8. Sujetos del caso: Editar

```
PATCH {urlPOL}/caso/sujetos/{polCasoPersonaId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSujetoId | int | requerido | |
| parentescoVictimaId | int | | |
| personaNatural | objeto | sí y no | Requerido solo si persona jurídica es null |
| personaJuridica | objeto | sí y no | Requerido solo si persona natural es null |
| esQuerellante | bool | | |
| estado | tinyint | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Sujeto actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.9. Abogados del sujeto: Crear

```
POST {urlPOL}/caso/sujetos/abogados
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaId | int | requerido | |
| abogados | array | requerido | Lista de objetos de abogados |

**Objeto abogado**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaAbogadoId | int | requerido | |
| codigoRPA | string | requerido | |
| ci | string | requerido | |
| nombres | string | requerido | |
| primerApellido | string | | |
| segundoApellido | string | | |
| fechaNacimiento | date | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Abogados registrados correctamente",
  "response": {
    "situacionJuridicas": [
      { "mpCasoPersonaAbogadoId": 1, "polCasoPersonaAbogadoId": 1 }
    ]
  },
  "status": 200
}
```

### 3.10. Abogados del sujeto: Editar

```
PATCH {urlPOL}/caso/sujetos/abogados/{polCasoPersonaAbogadoId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | tinyint | requerido | Para dar de baja al abogado |
| motivoBaja | string | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Abogados actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.11. Situaciones jurídicas del sujeto: Crear

```
POST {urlPOL}/caso/sujetos/situacion-juridicas
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaId | int | requerido | |
| situacionesJuridicas | array | requerido | Lista de objetos de situaciones jurídicas |

**Objeto situación jurídica**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoPersonaSituacionJuridicaId | int | requerido | |
| situacionJuridicaId | int | requerido | Catálogo de situaciones jurídicas |
| fechaInicio | date | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Situación jurídica registrados correctamente",
  "response": {
    "situacionJuridicas": [
      { "mpCasoPersonaSituacionJuridicaId": 1, "polCasoPersonaSituacionJuridicaId": 1 }
    ]
  },
  "status": 200
}
```

### 3.12. Domicilios del sujeto: Crear

```
POST {urlPOL}/caso/sujetos/domicilios
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpPersonaDomicilioId | int | requerido | Enviado para mantener integridad de información; con lo cual OJ también responderá con qué id se ha almacenado en su BD (ojPersonaResidenciaId) |
| polCasoPersonaId | int | requerido | |
| paisId | int | requerido | |
| municipioId | int | | |
| direccion | string | | |
| latitud | string | | |
| longitud | string | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Residencias registrado correctamente",
  "response": { "polPersonaResidenciaId": 1 },
  "status": 200
}
```

### 3.13. Fiscales del caso: Crear

```
POST {urlPOL}/caso/investigadores
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | |
| fiscales | array | requerido | Lista de objetos de fiscales |

**Objeto fiscal**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoFuncionarioId | int | requerido | |
| tipoResponsableId | int | requerido | Catálogo de ministerio público |
| ci | string | requerido | |
| nombres | string | requerido | |
| primerApellido | string | | |
| segundoApellido | string | | |
| fechaNacimiento | date | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Fiscal registrados correctamente",
  "response": [{ "mpCasoFuncionarioId": 1, "polCasoFuncionarioId": 1 }],
  "status": 200
}
```

### 3.14. Investigadores del caso: Editar

```
PATCH {urlPOL}/caso/investigadores/{polCasoFuncionarioId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | tinyint | requerido | Para dar de baja investigador |

**Respuesta:**

```json
{
  "error": false,
  "message": "Fiscal actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.15. Actividades del caso: Crear

```
POST {urlPOL}/caso/actividades
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | |
| actividades | array | requerido | Lista de objetos de actividades |

**Objeto actividad**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpCasoActividadId | int | requerido | |
| respuestaOjCasoActividadId | int | | |
| actividadId | int | requerido | |
| referencia | string | requerido | |
| archivoExtension | string | | Ejemplo: pdf, mp3, mp4 |
| archivoLink | string | | |
| archivoTamano | int | | Tamaño del fichero en bytes |
| archivoPaginas | int | | Cantidad de páginas del documento |
| archivoHash | string | requerido | |
| aprobacionesCD | array | | Lista de objetos usuario |
| firmasDigitales | array | | Lista de objetos usuario |
| metaData | objeto | | Objeto metadata |

**7. Objeto metadata (Solicitud detención preventiva y ampliación detención preventiva)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| sujetos | array | requerido | Lista de objetos de detención preventiva |

*Objeto detención preventiva*

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaId | int | requerido | |
| tiempoDias | int | requerido | Tiempo solicitado |
| riesgosProcesalesId | array | | Lista de id's de riesgos procesales |

**8. Objeto metadata (Solicitud sentencia condenatoria)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| sujetos | array | requerido | Lista de objetos sentencia condenatoria |

*Objeto solicitud sentencia condenatoria*

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaId | int | requerido | |
| tiempoPenaDias | int | requerido | Tiempo solicitado privación de libertad |
| tiempoMultaDias | int | | Económico |
| tiempoTrabajoDias | int | | Trabajo comunitario |

**9. Objeto metadata (Solicitud otorgamiento de medidas de protección)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| sujetos | array | requerido | Lista de objetos de medidas de protección |

*Objeto de medidas de protección*

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaId | int | requerido | |
| medidasProteccionId | array | requerido | Lista de id's de medidas de protección |

**10. Objeto metadata (Solicitud ampliar plazos procesales)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| tiempoDias | int | requerido | Tiempo solicitado para ampliación |

**11. Objeto metadata (Solicitud paralizar plazos procesales)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |

**12. Objeto metadata (Solicitud dar de baja actividad)**

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoSolicitudId | int | requerido | Catálogo con parámetros del metadata |
| referenciaOjCasoActividadId | int | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Actividades registrados correctamente",
  "response": [{ "mpCasoActividadId": 1, "polCasoActividadId": 1 }],
  "status": 200
}
```

### 3.16. Reserva de: caso, sujeto, actividad

> `tabla`: 1 = caso, 2 = sujeto, 3 = actividades
> `tablaId`: polCasoId, polCasoPersonaId, polCasoActividadId

```
POST {urlPOL}/set/reserva
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| estado | int | requerido | |
| tabla | int | requerido | |
| tablaId | int | requerido | |
| fechaFinReserva | date | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Reserva registrado correctamente",
  "response": null,
  "status": 200
}
```

### 3.16b. Agenda de audiencias: Editar

```
PATCH {urlPOL}/caso/agenda/{polAgendaId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| tipoEventoId | int | requerido | |
| tipoSubEventoId | int | | |
| juzgadoId | int | requerido | |
| titulo | string | requerido | |
| descripcion | string | requerido | |
| estado | tinyint | | |
| latitud | string | | |
| longitud | string | | |
| fechaInicio | date | requerido | |
| fechaFin | date | requerido | |
| esVirtual | bool | | |
| direccion | string | | |
| linkReunion | string | | |
| conclusionId | int | | |
| mpCasoPersonaIds | array | | |
| mpCasoFuncionariosIds | array | | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Agenda actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.17. Juzgado del caso: Actualizar

```
POST {urlPOL}/caso/juzgado
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | Se actualiza juzgado del caso en primera instancia |
| juzgadoId | int | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Juzgado actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.18. Juzgado del sujeto: Actualizar

```
POST {urlPOL}/caso/sujeto/juzgado
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoPersonaIds | array | requerido | Se actualiza juzgado de sujeto en situación de ser necesaria |
| juzgadoId | int | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "Juzgado actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.19. Agenda de audiencias: Crear

```
POST {urlPOL}/caso/agenda
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| polCasoId | int | requerido | |
| ojAudienciaId | int | requerido | |
| ojAudienciaDetalleId | int | requerido | |
| tipoAudienciaId | int | requerido | |
| tipoActividadId | int | requerido | |
| juzgadoId | int | requerido | |
| salaAudienciaId | int | requerido | |
| estadoAudienciaId | int | requerido | |
| modalidad | tinyint | | 1: presencial, 2: virtual, 3: mixta |
| fechaHoraInicio | date | requerido | |
| fechaHoraFin | date | requerido | |
| descripcion | string | requerido | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| latitud | string | | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| longitud | string | | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| direccion | string | | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| enlaceAudiencia | string | | |
| mpCasoPersonaIds | array | | Notificados para la audiencia |

**Respuesta:**

```json
{
  "error": false,
  "message": "Agenda registrado correctamente",
  "response": { "polAgendaId": 1 },
  "status": 201
}
```

### 3.20. Agenda de audiencias: Editar

```
PATCH {urlPOL}/caso/agenda/{polAgendaId}
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| mpAudienciaDetalleId | int | requerido | |
| tipoAudienciaId | int | requerido | |
| tipoActividadId | int | requerido | |
| juzgadoId | int | requerido | |
| salaAudienciaId | int | requerido | |
| estadoAudienciaId | int | requerido | |
| modalidad | tinyint | | 1: presencial, 2: virtual, 3: mixta |
| fechaHoraInicio | date | requerido | |
| fechaHoraFin | date | requerido | |
| descripcion | string | requerido | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| latitud | string | | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| longitud | string | | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| direccion | string | | Cuando se realiza en lugar distinto a las salas de audiencia del OJ |
| fechaHoraInicioGrabacion | date | | |
| duracionGrabacion | time | | |
| enlaceAudiencia | string | | |
| archivoExtension | string | | Ejemplo: mp4, mkv |
| archivoEnlace | string | | |
| archivoTamano | int | | Tamaño del fichero en bytes |
| archivoHash | string | | |
| suspencionAudienciaId | int | | |
| observaciones | string | | De la suspensión de audiencia |
| mpCasoPersonaIds | array | | Asistentes en la audiencia |

**Respuesta:**

```json
{
  "error": false,
  "message": "Agenda actualizado correctamente",
  "response": null,
  "status": 200
}
```

### 3.21. Descargar Archivo desde URL

Servicio para descargar el archivo (en base 64 u otro) para tenerlo en nuestro sistema.

```
GET {urlPOL}/{archivoHash}?user=usuarioCi
```

| Parámetro | Tipo | Estado | Descripción |
|---|---|---|---|
| archivoHash | string | requerido | |
| usuarioCi | string | requerido | |

**Respuesta:**

```json
{
  "error": false,
  "message": "documento obtenido",
  "response": { "data": "BASE64aasfddddgfgfffggrrrrrdfddfdsf…" },
  "status": 200
}
```
