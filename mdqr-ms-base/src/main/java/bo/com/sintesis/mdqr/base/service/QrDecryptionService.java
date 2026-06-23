package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.security.SecurityUtils;
import bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException;
import bo.com.sintesis.mdqr.base.service.exception.InvalidQrFormatException;
import bo.com.sintesis.mdqr.base.web.rest.dto.DecryptQrRequest;
import bo.com.sintesis.mdqr.base.web.rest.dto.DecryptQrResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio principal para desencriptación de códigos QR.
 * <p>
 * Responsabilidades:
 * - Parsing del formato QR: {encrypted_base64}|{certificate_code}
 * - Obtención del certificado (desde caché o Tuxedo API)
 * - Desencriptación RSA con BouncyCastle
 * - Detección del tipo de QR basado en contenido
 * - Auditoría asíncrona de cada desencriptación
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QrDecryptionService {

    /**
     * Patrón del formato QR: {encrypted_data}|{certificate_code}
     * El código de certificado debe ser hexadecimal (A-F0-9, case insensitive).
     */
    private static final Pattern QR_PATTERN = Pattern.compile("^(.+)\\|([A-Fa-f0-9]+)$");

    private final CertificateService certificateService;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    /**
     * Desencripta un código QR completo.
     *
     * @param request Request con el QR string y metadata
     * @return Response con los datos desencriptados y metadata
     * @throws InvalidQrFormatException si el formato del QR es inválido
     */
    public DecryptQrResponse decrypt(DecryptQrRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("Iniciando desencriptación de QR");

        try {
            // 1. Validar y parsear el QR
            QrComponents components = parseQr(request.getQrString());
            log.debug("QR parseado: certificateCode={}", components.getCertificateCode());

            // 2. Obtener el certificado PEM desde base de datos
            String certificatePem = certificateService.getCertificatePemBySerial(components.getCertificateCode());
            log.debug("Certificado PEM obtenido desde base de datos");

            // 3. Desencriptar con RSA
            String decryptedData = cryptoService.decryptRSA(
                components.getEncryptedData(),
                certificatePem
            );
            log.info("Datos desencriptados exitosamente: {} caracteres", decryptedData.length());

            // 4. Detectar tipo de QR basado en contenido
            String qrType = detectQrType(decryptedData);

            // 5. Calcular tiempo de procesamiento
            long processingTime = System.currentTimeMillis() - startTime;

            // 6. Generar ID único para el log
            String logId = generateLogId();

            // 7. Construir response
            DecryptQrResponse response = DecryptQrResponse.builder()
                .logId(logId)
                .decryptedData(decryptedData)
                .certificateCode(components.getCertificateCode())
                .entityId(request.getEntityIdRequest())
                .qrType(qrType)
                .processingTimeMs(processingTime)
                .decryptedAt(Instant.now())
                .fromCache(false)
                .build();

            // 8. Auditar de forma asíncrona
            auditService.logDecryption(
                logId,
                components.getCertificateCode(),
                request.getQrString(),
                true,
                null,
                processingTime,
                request.getEntityIdRequest(),
                qrType
            );

            log.info("Desencriptación completada exitosamente: logId={}, processingTime={}ms",
                logId, processingTime);

            return response;

        } catch (InvalidQrFormatException e) {
            // Error de formato - auditar fallo
            long processingTime = System.currentTimeMillis() - startTime;
            String logId = generateLogId();

            auditService.logDecryption(
                logId,
                null,
                request.getQrString(),
                false,
                "INVALID_QR_FORMAT: " + e.getMessage(),
                processingTime,
                request.getEntityIdRequest(),
                null
            );

            throw e;

        } catch (Exception e) {
            // Error general - auditar fallo
            long processingTime = System.currentTimeMillis() - startTime;
            String logId = generateLogId();

            auditService.logDecryption(
                logId,
                null,
                request.getQrString(),
                false,
                e.getClass().getSimpleName() + ": " + e.getMessage(),
                processingTime,
                request.getEntityIdRequest(),
                null
            );

            throw e;
        }
    }

    /**
     * Parsea un QR string en sus componentes.
     *
     * @param qrString String del QR en formato: {encrypted_data}|{certificate_code}
     * @return Componentes parseados
     * @throws InvalidQrFormatException si el formato es inválido
     */
    public QrComponents parseQr(String qrString) {
        // Validar null o vacío
        if (qrString == null || qrString.trim().isEmpty()) {
            throw InvalidQrFormatException.emptyQr();
        }

        // Validar que contenga el separador
        if (!qrString.contains("|")) {
            throw InvalidQrFormatException.missingSeparator();
        }

        // Aplicar regex pattern
        Matcher matcher = QR_PATTERN.matcher(qrString);

        if (!matcher.matches()) {
            log.warn("QR con formato inválido: {}", qrString.substring(0, Math.min(50, qrString.length())));
            throw InvalidQrFormatException.invalidFormat(qrString);
        }

        // Extraer componentes
        String encryptedData = matcher.group(1);
        String certificateCode = matcher.group(2);

        // Validar que no estén vacíos
        if (encryptedData.isEmpty()) {
            throw InvalidQrFormatException.emptyComponent("encrypted_data");
        }

        if (certificateCode.isEmpty()) {
            throw InvalidQrFormatException.emptyComponent("certificate_code");
        }

        log.debug("QR parseado exitosamente: certificateCode={}, encryptedDataLength={}",
            certificateCode, encryptedData.length());

        return QrComponents.builder()
            .encryptedData(encryptedData)
            .certificateCode(certificateCode)
            .build();
    }

    /**
     * Detecta el tipo de QR basándose en el contenido desencriptado.
     * Usa heurística simple para clasificar.
     *
     * @param decryptedData Datos desencriptados
     * @return Tipo de QR: PAYMENT, INVOICE, ACCOUNT, JSON, XML, UNKNOWN
     */
    public String detectQrType(String decryptedData) {
        if (decryptedData == null || decryptedData.isEmpty()) {
            return "UNKNOWN";
        }

        String data = decryptedData.trim().toLowerCase();

        // Detectar JSON
        if (data.startsWith("{") && data.endsWith("}")) {
            // Analizar contenido JSON para tipos específicos
            if (data.contains("\"amount\"") || data.contains("\"monto\"")) {
                return "PAYMENT";
            }
            if (data.contains("\"invoice\"") || data.contains("\"factura\"")) {
                return "INVOICE";
            }
            if (data.contains("\"account\"") || data.contains("\"cuenta\"")) {
                return "ACCOUNT";
            }
            return "JSON";
        }

        // Detectar XML
        if (data.startsWith("<?xml") || data.startsWith("<")) {
            return "XML";
        }

        // Detectar formato de pago simple (separado por pipes o comas)
        if (data.contains("|") && (data.contains("amount") || data.contains("monto"))) {
            return "PAYMENT";
        }

        // Detectar número de cuenta
        if (data.matches("^\\d{10,20}$")) {
            return "ACCOUNT";
        }

        log.debug("Tipo de QR no detectado, retornando UNKNOWN");
        return "UNKNOWN";
    }

    /**
     * Genera un ID único para el log de auditoría.
     * Usa formato ObjectId de MongoDB para compatibilidad.
     *
     * @return ID único como String
     */
    public String generateLogId() {
        return new ObjectId().toHexString();
    }

    /**
     * Componentes parseados de un QR.
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class QrComponents {
        /**
         * Datos encriptados en Base64.
         */
        private String encryptedData;

        /**
         * Código del certificado (hexadecimal).
         */
        private String certificateCode;
    }
}
