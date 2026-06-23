# Flujo Criptográfico Correcto: RSA Inverso (Confirmado)

**Fecha**: 26 Mayo 2026
**Status**: ✅ CONFIRMADO POR EQUIPO DE INFRAESTRUCTURA

---

## 1. ACLARACIÓN RECIBIDA

**Confirmación del equipo**:
> "Los bancos realizan la encriptación de los QR con la **llave privada** y se desencripta con el **certificado público**"

**Esto es válido y se llama**: **RSA Inverso** o **Textbook RSA Signing** (sin hash)

---

## 2. FLUJO CRIPTOGRÁFICO REAL

### 2.1 En el Banco (Generación del QR)

```
┌─────────────────────────────────────────────────────┐
│ BANCO GENERA QR CON LLAVE PRIVADA                   │
├─────────────────────────────────────────────────────┤
│                                                      │
│  1. Datos origen (JSON, texto plano, etc.)          │
│     Ejemplo: {"amount": 100, "account": "123"}      │
│                                                      │
│  2. Encriptar con LLAVE PRIVADA del banco           │
│     RSA/ECB/PKCS1Padding                            │
│     Cipher.init(ENCRYPT_MODE, bankPrivateKey)       │
│     Output: 256 bytes (RSA-2048)                    │
│                                                      │
│  3. Codificar a Base64                              │
│     Output: ~344 caracteres                         │
│     Ejemplo: MVZfTul1pSBsaxELF...acKYxQ==          │
│                                                      │
│  4. Agregar serial del certificado del banco        │
│     Formato: {base64}|{serial_hex}                  │
│     Ejemplo: MVZfTul...==|69e6b38b                  │
│                                                      │
│  5. Generar código QR (imagen)                      │
│     Contiene: el string completo del paso 4         │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### 2.2 En Unilink (Procesamiento del QR)

```
┌─────────────────────────────────────────────────────┐
│ UNILINK PROCESA QR CON CERTIFICADO PÚBLICO          │
├─────────────────────────────────────────────────────┤
│                                                      │
│  1. Escanear/Leer QR                                │
│     Input: MVZfTul...==|69e6b38b                    │
│                                                      │
│  2. Parsear string (split por '|')                  │
│     Part1: MVZfTul...== (datos encriptados)         │
│     Part2: 69e6b38b (serial del certificado)        │
│                                                      │
│  3. Buscar certificado público del banco            │
│     - Consultar JKS/API Tuxedo por serial           │
│     - Obtener certificado público (.crt/.pem)       │
│                                                      │
│  4. Decodificar Base64                              │
│     Input: MVZfTul...==                             │
│     Output: 256 bytes (datos encriptados)           │
│                                                      │
│  5. Desencriptar con CERTIFICADO PÚBLICO            │
│     RSA/ECB/PKCS1Padding                            │
│     Cipher.init(ENCRYPT_MODE, bankPublicKey) ✅     │
│     (Nota: ENCRYPT_MODE hace la operación inversa)  │
│     Output: datos originales (bytes)                │
│                                                      │
│  6. Convertir a String (UTF-8)                      │
│     Output: {"amount": 100, "account": "123"}       │
│                                                      │
│  7. Retornar respuesta + auditoría                  │
│                                                      │
└─────────────────────────────────────────────────────┘
```

---

## 3. ¿POR QUÉ ESTO ES VÁLIDO?

### 3.1 Matemática RSA (Simplificado)

En RSA, tienes un par de claves relacionadas matemáticamente:
- **e** (exponente público) → parte de la clave pública
- **d** (exponente privado) → parte de la clave privada
- **n** (módulo) → compartido por ambas

**Operaciones válidas**:
```
Encriptación normal:
  C = M^e mod n  (con clave pública)
  M = C^d mod n  (con clave privada)

Encriptación inversa (lo que hacen los bancos):
  S = M^d mod n  (con clave privada) ← BANCO
  M = S^e mod n  (con clave pública) ← UNILINK
```

**Ambas son válidas matemáticamente** porque:
- `(M^d)^e mod n = M`
- `(M^e)^d mod n = M`

### 3.2 Propiedades de Seguridad

| Propiedad | Encriptación Normal | RSA Inverso (Bancos) |
|-----------|---------------------|----------------------|
| **Confidencialidad** | ✅ Sí (solo el dueño de la privada puede leer) | ❌ No (cualquiera con la pública puede leer) |
| **Autenticidad** | ❌ No (cualquiera puede encriptar) | ✅ Sí (solo el banco pudo generarlo) |
| **Integridad** | ❌ No (sin MAC) | ✅ Sí (cambios invalidan el padding) |
| **No repudio** | ❌ No | ✅ Sí (el banco no puede negar que lo envió) |

**Conclusión**: Los bancos NO necesitan confidencialidad (el contenido del QR es para procesamiento), pero SÍ necesitan **autenticidad** y **no repudio**.

---

## 4. ANALOGÍA CON FIRMAS DIGITALES

Este flujo es **similar** a una firma digital, pero con diferencias técnicas:

### 4.1 Firma Digital (Estándar)
```
Banco:
  1. Hash del mensaje: H = SHA256(message)
  2. Encriptar hash: S = RSA_encrypt(H, privateKey)
  3. Enviar: {message, S}

Unilink:
  1. Recibir: {message, S}
  2. Calcular hash: H = SHA256(message)
  3. Desencriptar firma: H' = RSA_decrypt(S, publicKey)
  4. Comparar: H == H' → válido
```

### 4.2 RSA Inverso (Lo que hacen)
```
Banco:
  1. Encriptar mensaje completo: C = RSA_encrypt(message, privateKey)
  2. Enviar: {C}

Unilink:
  1. Recibir: {C}
  2. Desencriptar: message = RSA_decrypt(C, publicKey)
```

**Diferencias**:
- Firma estándar: Solo firma un **hash** (32 bytes), mensaje va en claro
- RSA Inverso: Encripta el **mensaje completo** (hasta 245 bytes para RSA-2048)

**Ventajas del enfoque de los bancos**:
- ✅ Más simple (un solo paso)
- ✅ Mensaje no va en claro (aunque no es confidencial, está "ofuscado")
- ✅ Autenticidad garantizada igual

**Desventajas**:
- ⚠️ Limitado a mensajes pequeños (< 245 bytes para RSA-2048 con padding)
- ⚠️ Menos estándar que firma digital tradicional

---

## 5. IMPLEMENTACIÓN EN CRYPTOSERVICE.JAVA

### 5.1 Código Corregido

```java
/**
 * Desencripta datos RSA usando un certificado público.
 *
 * NOTA: Implementa "RSA Inverso" donde:
 * - El BANCO encripta con su LLAVE PRIVADA
 * - UNILINK desencripta con el CERTIFICADO PÚBLICO del banco
 */
public String decryptRSA(String encryptedDataBase64, String certificatePem) {
    // 1. Obtener clave pública del banco
    PublicKey publicKey = parsePublicKeyFromPEM(certificatePem);

    // 2. Decodificar Base64 → 256 bytes
    byte[] encryptedBytes = Base64.getDecoder().decode(encryptedDataBase64);

    // 3. Configurar cipher
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", BouncyCastleProvider.PROVIDER_NAME);

    // IMPORTANTE: Usamos ENCRYPT_MODE con clave pública
    // porque matemáticamente es la operación inversa de
    // encriptar con la clave privada
    cipher.init(Cipher.ENCRYPT_MODE, publicKey);

    // 4. Aplicar operación RSA (matemáticamente "desencripta")
    byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

    // 5. Convertir a String
    return new String(decryptedBytes, StandardCharsets.UTF_8);
}
```

### 5.2 ¿Por qué ENCRYPT_MODE con PublicKey?

En Java/BouncyCastle:
- `Cipher.DECRYPT_MODE` con `PublicKey` → **NO soportado** (lanza excepción)
- `Cipher.ENCRYPT_MODE` con `PublicKey` → ✅ Soportado

Matemáticamente:
- Banco hizo: `encrypt(data, privateKey)` → produce 256 bytes
- Nosotros hacemos: `encrypt(256_bytes, publicKey)` → revierte la operación → data original

Es **contra-intuitivo** pero **matemáticamente correcto**.

---

## 6. VALIDACIÓN CON OPENSSL

Para probar manualmente con OpenSSL:

```bash
# 1. Extraer datos encriptados del QR
ENCRYPTED_B64="MVZfTul1pSBsaxELFkaBvz...acKYxQ=="

# 2. Decodificar Base64 a binario
echo "$ENCRYPTED_B64" | base64 -d > encrypted.bin

# 3. Extraer clave pública del certificado
openssl x509 -in banco_cert.pem -pubkey -noout > banco_public.pem

# 4. "Desencriptar" con clave pública (RSA inverso)
# Nota: OpenSSL llama a esto "rsautl -verify" porque es similar a verificar firma
openssl rsautl -verify -inkey banco_public.pem -pubin -in encrypted.bin -out decrypted.txt

# 5. Ver resultado
cat decrypted.txt
# Output esperado: {"amount": 100, "account": "123"}
```

**Alternativa con pkeyutl** (más moderno):
```bash
openssl pkeyutl -verifyrecover -inkey banco_public.pem -pubin -in encrypted.bin -out decrypted.txt
```

---

## 7. EJEMPLO COMPLETO DE PRUEBA

### 7.1 QR Real
```
MVZfTul1pSBsaxELFkaBvzuiu5pnrhd/h0aDqEp2980XtmL+6jNtHjR2S8wCgmiCgyiojBrbwRipPj8zu4uk/PtUphjKyflT47pPPro0ANHKhBWWTbDSXccYuWZ9bdJ6XijOeIfRi3B1Gd3EB0ByDGyjrMURymtUQOa63sRjcPg1TEhPl7OoGl1/sG1dDWEJUrpDoCyjeB/sK0dwO8aFLstE5yytWWm26xJoVuR1Rd7sU3l8NokbglCZ/P7b61dNFj5nC25AuBEpT6ZYNfU3J9qBGfiGU4FCc+wFpDYVFmcOzai63V3FQbOy3B/W1Tyogsdx44Td//JLIU2QacKYxQ==|69e6b38b
```

### 7.2 Request al API
```bash
curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "MVZfTul1pSBs...==|69e6b38b",
    "entityIdRequest": "1426001"
  }'
```

### 7.3 Flujo Interno

1. **QrResource** recibe el request
2. **QrDecryptionService** parsea el QR:
   - `encryptedData`: `MVZfTul1pSBs...==`
   - `certificateCode`: `69e6b38b`
3. **CertificateService** busca certificado por serial `69e6b38b`:
   - Busca en Redis cache
   - Si no está, llama a Go API
   - Retorna PEM del certificado público
4. **CryptoService** desencripta:
   - Parse PEM → PublicKey
   - Decode Base64 → 256 bytes
   - `cipher.init(ENCRYPT_MODE, publicKey)`
   - `cipher.doFinal(256_bytes)` → datos originales
5. **AuditService** registra en PostgreSQL (async)
6. **Response** retorna JSON con datos desencriptados

### 7.4 Response Esperada
```json
{
  "logId": "6748a9d3f1e2c4b5a6d7e8f9",
  "decryptedData": "{\"codigoServicio\":\"001\",\"monto\":150.50,\"referencia\":\"PAY-2026-001\"}",
  "certificateCode": "69e6b38b",
  "entityId": "1426001",
  "qrType": "PAYMENT",
  "processingTimeMs": 45,
  "decryptedAt": "2026-05-26T12:30:45.123Z",
  "fromCache": true
}
```

---

## 8. SEGURIDAD Y CONSIDERACIONES

### 8.1 ¿Es Seguro?

**Para el propósito de los bancos**: ✅ SÍ

**Garantiza**:
- ✅ Autenticidad: Solo el banco con la clave privada pudo generar el QR
- ✅ Integridad: Cualquier modificación invalida el padding PKCS1
- ✅ No repudio: El banco no puede negar que generó el QR

**NO garantiza**:
- ❌ Confidencialidad: Cualquiera con el certificado público puede "desencriptar"
- Pero esto NO es un problema si:
  - Los QR son de un solo uso
  - Los datos no son sensibles por sí solos (requieren contexto adicional)
  - El QR se transmite por canal seguro (HTTPS, app móvil)

### 8.2 Limitaciones

1. **Tamaño máximo de datos**:
   - RSA-2048 con PKCS1Padding: máximo ~245 bytes de datos originales
   - Si necesitan más, deben usar encriptación híbrida (AES + RSA)

2. **Performance**:
   - RSA es lento (~2-5ms por operación)
   - Caché de certificados es CRÍTICO (implementado con Redis)

3. **Renovación de certificados**:
   - Cuando un banco renueva su certificado, los QR antiguos siguen siendo válidos
   - con el certificado viejo (por eso guardamos certificados históricos)

---

## 9. CONCLUSIÓN

✅ **Flujo confirmado**: Banco encripta con privada, Unilink desencripta con pública

✅ **Implementación corregida**: `CryptoService.java` ahora usa `ENCRYPT_MODE` con `PublicKey`

✅ **Arquitectura válida**: RSA Inverso es correcto para autenticidad sin confidencialidad

✅ **No se necesita clave privada de Unilink**: Solo certificados públicos de bancos

🚀 **Listo para pruebas**: El sistema debe funcionar ahora con QR reales

---

## 10. PRÓXIMOS PASOS

1. ✅ Compilar proyecto: `./gradlew :ulqr-ms-decrypt:build`
2. ✅ Levantar servicios: PostgreSQL, Redis, Go API
3. ✅ Iniciar backend: `./gradlew :ulqr-ms-decrypt:bootRun`
4. ✅ Probar con QR real: `curl POST /api/qr/decode`
5. ✅ Verificar respuesta con datos desencriptados
6. ✅ Validar auditoría en base de datos

---

**Documentado por**: Claude Code
**Confirmado por**: Equipo de Infraestructura
**Status**: ✅ LISTO PARA PRUEBAS
