> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# Solicitud de Recursos de Testing - Sistema ULQR QR Decrypt

**Para:** Área de Producto / Entidades Financieras Participantes
**De:** Equipo de Desarrollo - Backend
**Fecha:** 02 Junio 2026
**Prioridad:** 🔴 Alta

---

## 1. Situación Actual

Durante las pruebas iniciales del sistema de desencriptación de códigos QR (ULQR), se identificó una limitación importante en los datos de prueba disponibles:

### Problema Identificado

- De todos los QRs contenidos en el archivo Excel de pruebas, **solo 2 QRs pudieron coincidir exitosamente con sus certificados públicos correspondientes**.
- Esta cantidad es insuficiente para validar los múltiples escenarios de negocio definidos en las reglas del sistema.

### Impacto

- ❌ No se pueden realizar pruebas de regresión completas
- ❌ Los escenarios críticos de seguridad no están cubiertos (certificados revocados, QRs caducados)
- ❌ No es posible validar el comportamiento con múltiples certificados por entidad
- ⚠️ Riesgo de bugs no detectados en producción

---

## 2. Escenarios de Prueba Requeridos

Según las reglas de negocio aprobadas, el sistema debe soportar los siguientes escenarios:

### 2.1. Escenarios de Validez Temporal

| Escenario | Descripción | Criticidad |
|-----------|-------------|------------|
| ✅ **QRs vigentes** | QRs generados recientemente (< 30 días) | 🔴 Alta |
| ⏱️ **QRs próximos a caducar** | QRs con antigüedad entre 9-12 meses | 🟡 Media |
| ❌ **QRs caducados** | QRs con más de 1 año de antigüedad (deben rechazarse) | 🔴 Alta |

### 2.2. Escenarios de Certificados

| Escenario | Descripción | Criticidad |
|-----------|-------------|------------|
| ✅ **Certificados activos** | Certificados vigentes en uso | 🔴 Alta |
| 🔄 **Certificados reemplazados (superseded)** | Certificados que fueron reemplazados pero siguen válidos | 🔴 Alta |
| 🔒 **Certificados revocados** | Certificados comprometidos o invalidados | 🔴 Crítica |
| ⏰ **Certificados próximos a expirar** | Certificados con < 30 días de validez | 🟡 Media |
| ❌ **Certificados expirados** | Certificados fuera de su período de validez | 🔴 Alta |

### 2.3. Escenarios de Múltiples Certificados

| Escenario | Descripción | Criticidad |
|-----------|-------------|------------|
| 🏦 **Entidad con múltiples certificados activos** | Banco con varios certificados vigentes simultáneamente | 🔴 Alta |
| 🔄 **Transición entre certificados** | Período de gracia donde coexisten certificado antiguo y nuevo | 🔴 Alta |
| 📊 **Diferentes propósitos** | Certificados distintos para pagos vs transferencias | 🟡 Media |

### 2.4. Escenarios de Error

| Escenario | Descripción | Criticidad |
|-----------|-------------|------------|
| ❌ **Serial no encontrado** | QR con serial de certificado inexistente | 🔴 Alta |
| 🔐 **Desencriptación fallida** | QR con datos corruptos o formato inválido | 🔴 Alta |
| 🚫 **Certificado no confiable** | Certificado auto-firmado no autorizado | 🟡 Media |

---

## 3. Recursos Solicitados

Para cubrir todos los escenarios mencionados, solicitamos que cada entidad financiera participante proporcione el siguiente conjunto de datos de prueba:

### 3.1. Certificados Públicos (Formato Requerido)

#### Estructura de entrega

```
/certificados/
  ├── [CODIGO_ENTIDAD]_[SERIAL_NUMBER]_[ESTADO].pem
  │
  ├── Ejemplos:
  ├── 1016_69e6b38b_ACTIVO.pem
  ├── 1016_abc12345_SUPERSEDED.pem
  ├── 1016_xyz98765_REVOCADO.pem
  └── 1016_def54321_EXPIRADO.pem
```

#### Por cada entidad financiera (mínimo 5 certificados)

| Tipo | Cant. | Características |
|------|-------|-----------------|
| Certificado activo (principal) | 1 | Vigente, en uso actual |
| Certificado activo (secundario) | 1 | Vigente, para diferentes operaciones |
| Certificado superseded | 1 | Reemplazado hace < 6 meses, aún dentro de grace period |
| Certificado revocado | 1 | Revocado por seguridad o compromiso |
| Certificado expirado | 1 | Fuera de su período de validez |

#### Metadata requerida por certificado (archivo CSV)

```csv
codigo_entidad,serial_number,estado,fecha_emision,fecha_expiracion,proposito,notas
1016,69e6b38b,ACTIVO,2025-01-01,2026-12-31,pagos,Certificado principal
1016,abc12345,SUPERSEDED,2024-06-01,2026-06-30,pagos,Reemplazado por 69e6b38b
1016,xyz98765,REVOCADO,2024-01-01,2025-12-31,pagos,Comprometido 2025-08-15
1016,def54321,EXPIRADO,2023-01-01,2025-01-31,pagos,Certificado expirado
```

---

### 3.2. Códigos QR de Prueba (Formato Requerido)

#### Estructura de entrega

```
/qr_pruebas/
  ├── [CODIGO_ENTIDAD]/
  │   ├── texto/
  │   │   ├── qr_vigente_001.txt
  │   │   ├── qr_caducado_001.txt
  │   │   └── qr_revocado_001.txt
  │   │
  │   └── imagenes/
  │       ├── qr_vigente_001.png
  │       └── qr_vigente_002.png
```

#### Por cada entidad financiera (mínimo 15 QRs)

| Tipo | Cant. | Formato | Características |
|------|-------|---------|-----------------|
| QRs vigentes | 5 | 3 texto + 2 imagen | Generados con certificado activo, < 30 días |
| QRs antiguos pero válidos | 3 | texto | Generados hace 6-9 meses, con certificado superseded |
| QRs caducados | 2 | texto | Generados hace > 1 año |
| QRs con certificado revocado | 2 | texto | Generados con certificado posteriormente revocado |
| QRs con serial inexistente | 2 | texto | QRs inválidos para testing de error |
| QRs corruptos | 1 | texto | Datos encriptados inválidos |

#### Metadata requerida por QR (archivo CSV)

```csv
codigo_entidad,nombre_archivo,serial_certificado,fecha_generacion,estado_esperado,monto,moneda,descripcion
1016,qr_vigente_001.txt,69e6b38b,2026-05-15,SUCCESS,100.50,BOB,QR vigente para pago
1016,qr_caducado_001.txt,abc12345,2024-12-01,EXPIRED,250.00,BOB,QR caducado > 1 año
1016,qr_revocado_001.txt,xyz98765,2025-08-01,REVOKED,50.75,BOB,Cert revocado post-generación
1016,qr_vigente_002.txt,69e6b38b,2026-06-01,SUCCESS,75.25,BOB,QR vigente reciente
```

---

### 3.3. Formato de los QRs en Texto

#### Formato estándar

```
ENCRYPTED_DATA_BASE64|SERIAL_NUMBER_HEX
```

**Ejemplo:**
```
aGVsbG93b3JsZA==|69e6b38b
```

#### Especificaciones

- **ENCRYPTED_DATA**: Datos encriptados con la clave privada del banco, codificados en Base64
- **SERIAL_NUMBER**: Serial del certificado público en hexadecimal (8 caracteres)
- **Separador**: `|` (pipe/barra vertical)

#### Contenido Desencriptado (Ejemplo)

```json
{
  "amount": 100.50,
  "currency": "BOB",
  "merchantId": "COM-12345",
  "merchantName": "Comercio Ejemplo",
  "transactionId": "TXN-2026-001",
  "timestamp": "2026-06-02T18:30:00Z"
}
```

---

### 3.4. Formato de Imágenes de QR

#### Especificaciones técnicas

| Propiedad | Valor |
|-----------|-------|
| **Formato** | PNG (preferido) o JPG |
| **Resolución mínima** | 300x300 píxeles |
| **Resolución máxima** | 800x800 píxeles |
| **Calidad** | Alta (sin compresión excesiva) |
| **Contenido** | QR legible con ZXing library |
| **Fondo** | Blanco o transparente |
| **Contraste** | Alto (QR negro sobre fondo claro) |

---

## 4. Estructura de Entrega Final

```
/recursos_testing_ulqr/
│
├── README.md (instrucciones y contactos)
│
├── certificados/
│   ├── metadata_certificados.csv
│   ├── 1016_69e6b38b_ACTIVO.pem
│   ├── 1016_abc12345_SUPERSEDED.pem
│   ├── 1016_xyz98765_REVOCADO.pem
│   ├── 1016_def54321_EXPIRADO.pem
│   └── 1016_ghi67890_ACTIVO.pem
│
└── qr_pruebas/
    ├── metadata_qr.csv
    │
    ├── 1016_banco_economico/
    │   ├── texto/
    │   │   ├── qr_vigente_001.txt
    │   │   ├── qr_vigente_002.txt
    │   │   ├── qr_vigente_003.txt
    │   │   ├── qr_antiguo_valido_001.txt
    │   │   ├── qr_antiguo_valido_002.txt
    │   │   ├── qr_antiguo_valido_003.txt
    │   │   ├── qr_caducado_001.txt
    │   │   ├── qr_caducado_002.txt
    │   │   ├── qr_revocado_001.txt
    │   │   ├── qr_revocado_002.txt
    │   │   ├── qr_serial_inexistente_001.txt
    │   │   ├── qr_serial_inexistente_002.txt
    │   │   └── qr_corrupto_001.txt
    │   │
    │   └── imagenes/
    │       ├── qr_vigente_001.png
    │       ├── qr_vigente_002.png
    │       ├── qr_vigente_003.png
    │       ├── qr_vigente_004.png
    │       └── qr_vigente_005.png
    │
    ├── 1001_banco_nacional/
    │   └── ... (misma estructura)
    │
    ├── 1002_banco_mercantil/
    │   └── ... (misma estructura)
    │
    └── [otros_bancos]/
        └── ...
```

## 8. Justificación del Requerimiento

### 8.1. Impacto en Calidad

| Métrica | Sin Recursos Adecuados | Con Recursos Solicitados | Mejora |
|---------|------------------------|--------------------------|--------|
| **Casos de prueba** | 2 casos | 75+ casos | **+3,650%** |
| **Cobertura de escenarios** | < 5% | > 90% | **+1,700%** |
| **Entidades cubiertas** | 1 banco | 5 bancos | **+400%** |
| **Tipos de QR** | Solo vigentes | 6 tipos diferentes | **+500%** |

### 8.2. Riesgos Mitigados

- ✅ **Seguridad Crítica:** Detección de bugs en escenarios de certificados revocados
- ✅ **Compliance:** Validación de retención de logs y auditoría
- ✅ **Performance:** Testing con múltiples entidades y carga concurrente
- ✅ **Compatibilidad:** Verificación de QRs antiguos (retroactividad)
- ✅ **Operación:** Validación de procesos de reemplazo de certificados

### 8.3. Beneficios Esperados

| Beneficio | Impacto Estimado |
|-----------|------------------|
| 🎯 Reducción de incidentes en producción | -80% |
| 🎯 Confianza en estabilidad del sistema | Alta |
| 🎯 Documentación de casos de uso reales | Completa |
| 🎯 Base de datos de regresión | Permanente |
| 🎯 Tiempo de resolución de issues | -50% |

### 8.4. Costos de NO tener recursos adecuados

| Riesgo | Probabilidad | Impacto | Costo Estimado |
|--------|--------------|---------|----------------|
| Bug en producción con cert. revocado | Alta | Crítico | Pérdida de confianza |
| QR caducado aceptado erróneamente | Media | Alto | Fraude potencial |
| Performance issues con múltiples certs | Alta | Medio | Downtime |
| Rollback por bug no detectado | Media | Alto | Retraso en go-live |

---

## 9. Proceso de Validación de Recursos

Una vez recibidos los recursos, el equipo de desarrollo ejecutará el siguiente proceso:

### Fase 1: Validación Técnica (1 día)

1. **Certificados:**
   - [x] Parseo exitoso de archivos PEM
   - [x] Extracción de serial, issuer, subject
   - [x] Validación de fechas de validez
   - [x] Verificación de formato correcto

2. **QRs:**
   - [x] Lectura de archivos de texto
   - [x] Decodificación de imágenes con ZXing
   - [x] Validación de formato `DATA|SERIAL`
   - [x] Verificación de Base64 válido

3. **Metadata:**
   - [x] Parseo de CSV sin errores
   - [x] Validación de campos obligatorios
   - [x] Consistencia con archivos físicos

### Fase 2: Validación Funcional (1 día)

1. **Desencriptación:**
   - [x] QRs vigentes desencriptan correctamente (>80%)
   - [x] QRs caducados rechazan como se espera
   - [x] QRs con cert. revocado rechazan
   - [x] QRs con serial inexistente fallan apropiadamente

2. **Escenarios:**
   - [x] Múltiples certificados por entidad funcionan
   - [x] Transición entre certificados opera correctamente
   - [x] Cache y performance adecuados

### Fase 3: Reporte de Validación

Se generará un reporte con:
- ✅ Recursos aceptados
- ⚠️ Recursos con advertencias (se pueden usar pero hay issues menores)
- ❌ Recursos rechazados (no cumplen criterios mínimos)
- 📊 Estadísticas de cobertura alcanzada

---

## 11. Alternativas Consideradas

En caso de dificultad para obtener datos reales de las entidades financieras:

### Opción B - Datos Sintéticos

**Descripción:**
- Generar certificados de prueba auto-firmados
- Crear QRs sintéticos usando las claves generadas
- Simular diferentes estados (vigente, revocado, expirado)

**Ventajas:**
- ✅ Control total sobre los datos
- ✅ Disponibilidad inmediata
- ✅ No depende de terceros

**Limitaciones:**
- ❌ No valida integración real con sistemas bancarios
- ❌ Posibles diferencias en formato/estructura real
- ❌ No detecta issues específicos de cada banco

---

### Opción C - Datos Parciales

**Descripción:**
- Priorizar solo 3 entidades en lugar de 5
- Reducir escenarios a los más críticos:
  - QRs vigentes
  - Certificados revocados
  - QRs caducados

**Ventajas:**
- ✅ Menor esfuerzo de coordinación
- ✅ Tiempo de entrega más corto
- ✅ Cubre escenarios críticos

**Limitaciones:**
- ⚠️ Cobertura reducida (70% en lugar de 90%)
- ⚠️ No valida múltiples certificados por entidad
- ⚠️ Menos casos edge detectados

**Cobertura:** 45+ casos de prueba

---

### Recomendación

**Opción A (Datos Reales)** es **crítica** para la confiabilidad del sistema en producción.

Si hay retrasos:
1. Iniciar con Opción B (datos sintéticos) para comenzar testing
2. Reemplazar gradualmente con datos reales cuando estén disponibles
3. Priorizar Opción C (3 bancos) sobre Opción B

---

## 12. Preguntas Frecuentes (FAQ)

### ¿Por qué necesitan datos reales si pueden generar sintéticos?

Los datos reales validan:
- Formato exacto de QRs de cada banco
- Particularidades de implementación RSA
- Diferencias en estructura de certificados
- Casos edge no previstos

### ¿Qué pasa si un banco no puede proporcionar QRs caducados?

Opciones:
1. Generar QRs con certificados antiguos del banco (si disponibles)
2. Usar certificados con fecha de emisión modificada (testing)
3. Reducir ese escenario y compensar con otros

### ¿Los certificados revocados deben estar realmente revocados?

No. Para testing, basta con:
- Certificado marcado como "REVOCADO" en metadata
- Sistema lo trata como revocado en BD
- No necesita revocación real en CA

### ¿Qué tan confidencial es la información de los QRs?

Los QRs de prueba deben:
- ✅ Usar montos ficticios
- ✅ No contener datos reales de clientes
- ✅ Usar IDs de comercios de prueba
- ⚠️ Mantener formato y estructura reales

### ¿Qué hacemos si los QRs no desencriptan?

Proceso:
1. Verificar que QR y certificado sean del mismo banco
2. Validar serial del QR coincida con serial del certificado
3. Verificar formato del QR (DATA|SERIAL)
4. Contactar al banco para aclarar formato

--

**Documento preparado por:** Eitner M.
**Versión:** 1.0
**Fecha:** 02 Junio 2026
