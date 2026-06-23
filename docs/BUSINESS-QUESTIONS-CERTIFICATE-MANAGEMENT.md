# Preguntas de Negocio: Gestión de Certificados Públicos

**Fecha**: 28 Mayo 2026
**Para**: Área de Producto/Negocio
**De**: Equipo de Desarrollo
**Asunto**: Clarificación de reglas de negocio para certificados públicos

---

## Contexto

Estamos implementando el sistema de gestión de certificados públicos de bancos para desencriptar QRs de pago. Necesitamos clarificar varios escenarios de negocio antes de definir las reglas técnicas.

---

## Categoría 1: Ciclo de Vida de Certificados

### 1.1. Renovación y Reemplazo de Certificados

**Escenario**:
Un banco tiene un certificado vigente (Certificado A con serial "69e6b38b") que están usando actualmente. El banco nos envía un nuevo certificado (Certificado B con serial "abc12345") porque el anterior está por expirar.

**Preguntas**:

1. **¿Qué debe pasar con el Certificado A cuando recibimos el Certificado B?**
   - [ ] A) El Certificado A se invalida inmediatamente (los QRs antiguos dejarían de funcionar)
   - [ ] B) Ambos certificados coexisten y son válidos (QRs antiguos y nuevos funcionan)
   - [ ] C) Hay un período de transición (especificar cuántos días: ___ días)
   - [ ] D) Otro: _________________________________

2. **¿Los bancos nos notificarán explícitamente cuando un certificado reemplaza a otro?**
   - [ ] A) Sí, siempre nos dirán "este certificado reemplaza al anterior"
   - [ ] B) No, solo nos envían el nuevo certificado sin más contexto
   - [ ] C) Depende del banco
   - [ ] D) No lo sabemos aún

3. **¿Puede un banco tener múltiples certificados vigentes al mismo tiempo para diferentes propósitos?**
   - [ ] A) No, solo 1 certificado por banco siempre
   - [ ] B) Sí, pueden tener varios (ej: uno para pagos, otro para transferencias)
   - [ ] C) Sí, pero solo temporalmente durante transiciones
   - [ ] D) No lo sabemos aún

   **Si la respuesta es B**: ¿Cómo identificamos el propósito de cada certificado?
   - _________________________________________________

---

## Categoría 2: QRs Antiguos vs QRs Nuevos

### 2.1. Compatibilidad Retroactiva

**Escenario**:
Un cliente escaneó un QR de pago hace 2 meses (generado con Certificado A). Hoy quiere pagar, pero el banco ya actualizó al Certificado B.

**Preguntas**:

4. **¿Debe funcionar el QR antiguo (2 meses de antigüedad)?**
   - [ ] A) Sí, los QRs deben funcionar indefinidamente
   - [ ] B) Sí, pero solo durante un período específico (especificar: ___ meses)
   - [ ] C) No, los QRs tienen validez limitada
   - [ ] D) Depende del tipo de pago

   **Si C o D**: ¿Cuál es el tiempo de validez de un QR?
   - _________________________________________________

5. **¿Qué debe pasar si intentamos desencriptar un QR con un certificado que ya fue reemplazado?**
   - [ ] A) Debe funcionar normalmente (sin restricciones)
   - [ ] B) Debe funcionar pero generar una alerta/log especial
   - [ ] C) Debe rechazarse con error
   - [ ] D) Otro: _________________________________

---

## Categoría 3: Revocación y Seguridad

### 3.1. Certificados Comprometidos

**Escenario**:
Un banco nos notifica que su certificado fue comprometido (la clave privada se filtró) y debemos dejar de aceptar QRs generados con ese certificado inmediatamente.

**Preguntas**:

6. **¿Con qué frecuencia esperan que ocurra este escenario?**
   - [ ] A) Nunca debería pasar
   - [ ] B) Muy raramente (1 vez cada varios años)
   - [ ] C) Ocasionalmente (varias veces al año)
   - [ ] D) No lo sabemos

7. **¿Qué debe pasar con QRs pendientes de pago que usan un certificado revocado?**
   - [ ] A) Rechazarlos inmediatamente (seguridad > conveniencia)
   - [ ] B) Permitir un período de gracia (ej: 24 horas)
   - [ ] C) Contactar al banco para cada caso
   - [ ] D) Otro: _________________________________

8. **¿Quién es responsable de notificarnos sobre revocaciones?**
   - [ ] A) El banco nos notificará proactivamente
   - [ ] B) Nosotros debemos consultar CRLs (Certificate Revocation Lists)
   - [ ] C) Ambos
   - [ ] D) No se ha definido

9. **¿Qué nivel de urgencia tiene una revocación?**
   - [ ] A) Crítico - ejecutar inmediatamente (minutos)
   - [ ] B) Alta - ejecutar el mismo día (horas)
   - [ ] C) Media - ejecutar en 1-2 días
   - [ ] D) Depende del motivo de revocación

---

## Categoría 4: Proceso de Upload de Certificados

### 4.1. ¿Quién Sube los Certificados?

10. **¿Quién será responsable de subir certificados al sistema?**
    - [ ] A) Operadores de Unilink (internos)
    - [ ] B) Personal del banco
    - [ ] C) Administradores de ambas organizaciones
    - [ ] D) Sistema automatizado (API externa)

11. **¿Habrá un proceso de aprobación antes de activar un certificado?**
    - [ ] A) No, se activan automáticamente al subir
    - [ ] B) Sí, requiere aprobación de 1 persona
    - [ ] C) Sí, requiere aprobación de 2+ personas (dual control)
    - [ ] D) Depende del tipo de certificado o banco

12. **¿Los bancos nos enviarán certificados vía?**
    - [ ] A) Email
    - [ ] B) Portal web
    - [ ] C) API automatizada
    - [ ] D) USB/medios físicos
    - [ ] E) Otro: _________________________________

---

## Categoría 5: Validación y Verificación

### 5.1. Validaciones Requeridas

13. **¿Qué validaciones debemos hacer ANTES de aceptar un certificado?**
    - [ ] A) Solo validar que el formato PEM sea correcto
    - [ ] B) Verificar que el emisor (CA) sea confiable
    - [ ] C) Verificar que no esté expirado
    - [ ] D) Verificar contra una lista blanca de bancos autorizados
    - [ ] E) Todas las anteriores
    - [ ] F) Otro: _________________________________

14. **¿Debemos aceptar certificados auto-firmados (self-signed)?**
    - [ ] A) Sí, los bancos usan certificados auto-firmados
    - [ ] B) No, solo certificados de CAs reconocidas
    - [ ] C) Depende del banco
    - [ ] D) No lo sabemos

15. **¿Cuánto tiempo ANTES de expirar debemos alertar?**
    - [ ] A) 30 días antes
    - [ ] B) 60 días antes
    - [ ] C) 90 días antes
    - [ ] D) Otro: ___ días antes

---

## Categoría 6: Auditoría y Compliance

### 6.1. Requisitos de Auditoría

16. **¿Qué información debemos auditar sobre cada desencriptación de QR?**
    - [ ] A) Solo si fue exitosa o falló
    - [ ] B) Usuario que solicitó, timestamp, certificado usado
    - [ ] C) Todo lo anterior + IP, datos del QR (hash)
    - [ ] D) Todo lo anterior + monto, referencia del pago
    - [ ] E) Otro: _________________________________

17. **¿Por cuánto tiempo debemos retener los logs de auditoría?**
    - [ ] A) 6 meses
    - [ ] B) 1 año
    - [ ] C) 3 años
    - [ ] D) 7 años (regulatorio)
    - [ ] E) Indefinidamente

18. **¿Quién debe tener acceso a los logs de auditoría?**
    - [ ] A) Solo administradores de Unilink
    - [ ] B) Administradores + Compliance/Auditoría
    - [ ] C) Incluyendo personal del banco (solo sus datos)
    - [ ] D) Otro: _________________________________

19. **¿Necesitamos generar reportes automáticos de auditoría?**
    - [ ] A) No, solo consulta on-demand
    - [ ] B) Sí, reportes mensuales
    - [ ] C) Sí, reportes semanales
    - [ ] D) Sí, reportes diarios
    - [ ] E) Otro: _________________________________

---

## Categoría 7: Notificaciones y Alertas

### 7.1. ¿A Quién Notificar?

20. **¿A quién debemos notificar cuando un certificado está por expirar?**
    - [ ] A) Solo a operadores de Unilink
    - [ ] B) A operadores de Unilink + contactos del banco
    - [ ] C) Solo al banco
    - [ ] D) No enviar notificaciones (solo dashboard)

21. **¿Qué eventos requieren notificación inmediata?**
    - [ ] A) Certificado próximo a expirar (30 días)
    - [ ] B) Certificado expirado
    - [ ] C) Certificado revocado
    - [ ] D) Múltiples fallos de desencriptación
    - [ ] E) Todos los anteriores
    - [ ] F) Otro: _________________________________

22. **¿Qué canales de notificación necesitamos?**
    - [ ] A) Email
    - [ ] B) SMS
    - [ ] C) Dashboard web
    - [ ] D) Integración con sistema de tickets
    - [ ] E) Webhook/API
    - [ ] F) Varios de los anteriores (especificar): _________________

---

## Categoría 8: Escenarios Excepcionales

### 8.1. Problemas Operacionales

23. **Si un certificado falla repetidamente al desencriptar QRs, ¿qué hacemos?**
    - [ ] A) Suspender el certificado automáticamente después de X fallos
    - [ ] B) Alertar a operadores pero mantener el certificado activo
    - [ ] C) Requiere investigación manual caso por caso
    - [ ] D) Otro: _________________________________

    **Si A**: ¿Cuántos fallos consecutivos?: ___ fallos

24. **¿Puede un operador "desactivar temporalmente" un certificado sin revocarlo?**
    - [ ] A) Sí, necesitamos esta funcionalidad
    - [ ] B) No, solo ACTIVO o REVOCADO (sin estados intermedios)
    - [ ] C) Solo administradores senior pueden hacerlo
    - [ ] D) No lo sabemos

25. **Si subimos un certificado incorrecto por error, ¿podemos eliminarlo?**
    - [ ] A) Sí, eliminar físicamente (DELETE)
    - [ ] B) No, solo marcarlo como inválido (soft delete)
    - [ ] C) Solo si nunca se usó para desencriptar QRs
    - [ ] D) Otro: _________________________________

---

## Categoría 9: Performance y SLAs

### 9.1. Expectativas de Performance

26. **¿Cuál es el tiempo máximo aceptable para desencriptar un QR?**
    - [ ] A) < 100ms
    - [ ] B) < 500ms
    - [ ] C) < 1 segundo
    - [ ] D) < 2 segundos
    - [ ] E) No hay requisito específico

27. **¿Cuántos QRs esperan procesar simultáneamente en picos?**
    - [ ] A) < 10 QRs/segundo
    - [ ] B) 10-100 QRs/segundo
    - [ ] C) 100-1000 QRs/segundo
    - [ ] D) > 1000 QRs/segundo
    - [ ] E) No lo sabemos

28. **¿Cuántos bancos (certificados diferentes) esperan manejar?**
    - [ ] A) 1-10 bancos
    - [ ] B) 10-50 bancos
    - [ ] C) 50-100 bancos
    - [ ] D) > 100 bancos

---

## Categoría 10: Metadata y Organización

### 10.1. Clasificación de Certificados

29. **¿Necesitamos categorizar certificados por algún criterio?**
    - [ ] A) No, solo por banco
    - [ ] B) Sí, por tipo de operación (pagos, transferencias, etc.)
    - [ ] C) Sí, por ambiente (producción, testing)
    - [ ] D) Sí, por región geográfica
    - [ ] E) Varios de los anteriores (especificar): _________________

30. **¿Necesitamos poder "etiquetar" certificados con información custom?**
    - [ ] A) Sí, con tags libres (ej: "urgent", "deprecated")
    - [ ] B) Sí, con campos predefinidos
    - [ ] C) No es necesario
    - [ ] D) No lo sabemos

---

## Categoría 11: Integraciones Futuras

### 11.1. Roadmap

31. **¿Hay planes de automatizar la renovación de certificados en el futuro?**
    - [ ] A) Sí, queremos renovación automática (ACME protocol)
    - [ ] B) No, siempre será manual
    - [ ] C) Quizás en el futuro
    - [ ] D) No lo sabemos

32. **¿Necesitarán los bancos consultar el estado de sus certificados?**
    - [ ] A) Sí, necesitan API/portal para consultar
    - [ ] B) No, solo operadores de Unilink
    - [ ] C) Solo reportes periódicos por email
    - [ ] D) No lo sabemos

---

## Categoría 12: Rollback y Recuperación

### 12.1. Plan de Contingencia

33. **Si activamos un certificado nuevo y causa problemas, ¿podemos volver al anterior?**
    - [ ] A) Sí, debe ser posible hacer rollback inmediato
    - [ ] B) Sí, pero solo dentro de las primeras 24 horas
    - [ ] C) No, los cambios son definitivos
    - [ ] D) Depende de la situación

34. **¿Necesitamos mantener backup de todos los certificados históricos?**
    - [ ] A) Sí, indefinidamente
    - [ ] B) Sí, por un período específico (especificar: ___ años)
    - [ ] C) No, solo los actuales
    - [ ] D) Solo para auditoría/compliance

---

## Información Adicional

### Contexto Técnico (para ayudar a responder)

**Cómo funciona actualmente**:
1. Banco genera QR de pago encriptado con su clave privada
2. QR contiene: `datos_encriptados|serial_del_certificado`
3. Nuestro sistema busca el certificado público usando el serial
4. Desencripta los datos usando RSA Inverso
5. Procesa el pago

**Ejemplos de serials**:
- Certificado A: `69e6b38b`
- Certificado B: `abc12345`

---

## Preguntas Abiertas

35. **¿Hay algún otro escenario o regla de negocio que debamos considerar?**

    _____________________________________________________________
    _____________________________________________________________
    _____________________________________________________________

36. **¿Existen regulaciones o políticas internas que debamos cumplir?**

    _____________________________________________________________
    _____________________________________________________________
    _____________________________________________________________

37. **¿Cuál es el impacto en el negocio si un QR no se puede desencriptar?**
    - [ ] A) Crítico - pérdida de venta/transacción
    - [ ] B) Alto - cliente insatisfecho pero se puede resolver
    - [ ] C) Medio - se puede regenerar el QR
    - [ ] D) Bajo - es raro que pase

38. **¿Preferencias sobre la experiencia de usuario del sistema de gestión?**

    _____________________________________________________________
    _____________________________________________________________
    _____________________________________________________________

---

## Próximos Pasos

Una vez recibamos las respuestas a estas preguntas:

1. **Definiremos las reglas de negocio** en el sistema
2. **Diseñaremos el modelo de datos final** de acuerdo a sus respuestas
3. **Implementaremos la lógica** siguiendo las decisiones de negocio
4. **Crearemos un documento de especificación** para aprobación

---

**Fecha de envío**: _______________
**Fecha límite de respuesta**: _______________
**Contacto para dudas**: _______________

---

## Anexo: Glosario

- **Serial Number**: Identificador único del certificado (ej: `69e6b38b`)
- **PEM**: Formato de texto del certificado
- **Certificado Público**: Parte del certificado que se comparte (NO incluye clave privada)
- **Revocación**: Invalidar un certificado por seguridad
- **Superseded**: Certificado reemplazado por uno más reciente
- **Grace Period**: Período de transición donde ambos certificados son válidos

---

**Documento preparado por**: Equipo de Desarrollo
**Versión**: 1.0
**Fecha**: 28 Mayo 2026
