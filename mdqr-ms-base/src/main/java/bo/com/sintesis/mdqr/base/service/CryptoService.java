package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.service.exception.DecryptionException;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import jakarta.annotation.PostConstruct;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;

/**
 * Servicio de criptografía para operaciones RSA y manipulación de certificados.
 * <p>
 * Utiliza BouncyCastle para:
 * - Desencriptación RSA con certificados públicos
 * - Parsing de certificados PEM
 * - Extracción de metadata de certificados X.509
 * - Hashing SHA-256
 * </p>
 */
@Service
@Slf4j
public class CryptoService {

    private static final String RSA_ALGORITHM = "RSA/ECB/PKCS1Padding";
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Registra el provider de BouncyCastle al iniciar.
     */
    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            log.info("BouncyCastle provider registrado exitosamente");
        } else {
            log.debug("BouncyCastle provider ya estaba registrado");
        }
    }

    /**
     * Desencripta datos RSA usando un certificado público.
     *
     * NOTA IMPORTANTE: Este método implementa "RSA Inverso" donde:
     * - El BANCO encripta con su LLAVE PRIVADA
     * - UNILINK desencripta con el CERTIFICADO PÚBLICO del banco
     *
     * Esto NO proporciona confidencialidad (cualquiera puede leer los datos),
     * pero SÍ proporciona autenticidad y no-repudio (solo el banco pudo generarlo).
     *
     * Matemáticamente: decrypt(encrypt(data, privateKey), publicKey) = data
     *
     * @param encryptedDataBase64 Datos encriptados en Base64 (256 bytes para RSA-2048)
     * @param certificatePem Certificado público del banco en formato PEM
     * @return Datos desencriptados como String
     * @throws DecryptionException si hay error en la desencriptación
     */
    public String decryptRSA(String encryptedDataBase64, String certificatePem) {
        try {
            log.debug("Iniciando desencriptación RSA con clave pública (RSA inverso)");

            // 1. Parse del certificado PEM para obtener la clave pública del banco
            PublicKey publicKey = parsePublicKeyFromPEM(certificatePem);
            log.debug("Clave pública extraída: algoritmo={}, formato={}",
                publicKey.getAlgorithm(), publicKey.getFormat());

            // 2. Decodificar Base64
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedDataBase64);
            log.debug("Datos encriptados decodificados: {} bytes (esperados: 256 para RSA-2048)",
                encryptedBytes.length);

            // Validar tamaño esperado (256 bytes = RSA-2048)
            if (encryptedBytes.length != 256) {
                log.warn("Tamaño inesperado: {} bytes (esperado: 256 para RSA-2048)", encryptedBytes.length);
            }

            // 3. Configurar cipher RSA para "desencriptación con clave pública"
            // IMPORTANTE: En Java/BouncyCastle, para hacer RSA inverso (desencriptar con pública),
            // usamos DECRYPT_MODE con la clave pública, que matemáticamente revierte
            // la operación de encriptar con la clave privada.
            Cipher cipher = Cipher.getInstance(RSA_ALGORITHM, BouncyCastleProvider.PROVIDER_NAME);

            // Usamos DECRYPT_MODE con clave pública para RSA inverso:
            // - Bank: Cipher.init(ENCRYPT_MODE, privateKey)  → produce 256 bytes
            // - Unilink: Cipher.init(DECRYPT_MODE, publicKey) → revierte la operación
            cipher.init(Cipher.DECRYPT_MODE, publicKey);

            // 4. "Desencriptar" (realmente estamos aplicando la operación RSA inversa)
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            // 5. Convertir a String
            String decryptedData = new String(decryptedBytes, StandardCharsets.UTF_8);

            log.info("Desencriptación RSA exitosa: {} bytes → {} bytes (texto plano)",
                encryptedBytes.length, decryptedBytes.length);
            log.debug("Primeros 100 caracteres: {}",
                decryptedData.substring(0, Math.min(100, decryptedData.length())));

            return decryptedData;

        } catch (IllegalArgumentException e) {
            log.error("Error al decodificar Base64: {}", e.getMessage());
            throw new DecryptionException("Los datos encriptados no están en formato Base64 válido", e);

        } catch (javax.crypto.BadPaddingException e) {
            log.error("Error de padding RSA: {}", e.getMessage());
            throw new DecryptionException(
                "Error de padding al desencriptar. Posibles causas: " +
                "1) Certificado incorrecto, " +
                "2) Datos corruptos, " +
                "3) Datos no fueron encriptados con la clave privada correspondiente", e);

        } catch (javax.crypto.IllegalBlockSizeException e) {
            log.error("Tamaño de bloque RSA inválido: {}", e.getMessage());
            throw new DecryptionException(
                "Tamaño de bloque inválido. Esperado: 256 bytes para RSA-2048", e);

        } catch (Exception e) {
            log.error("Error inesperado al desencriptar con RSA: {}", e.getMessage(), e);
            throw DecryptionException.rsaDecryptionFailed(e);
        }
    }

    /**
     * Parsea un certificado PEM y extrae la clave pública.
     *
     * @param certificatePem Certificado en formato PEM
     * @return PublicKey extraída del certificado
     * @throws DecryptionException si hay error al parsear
     */
    public PublicKey parsePublicKeyFromPEM(String certificatePem) {
        try {
            log.debug("Parseando certificado PEM");

            // Limpiar el PEM de espacios y caracteres extra
            String cleanPem = certificatePem.trim();

            // Validar formato PEM
            if (!cleanPem.contains("BEGIN CERTIFICATE")) {
                throw new IllegalArgumentException(
                    "El PEM no contiene el header 'BEGIN CERTIFICATE'. Formato inválido."
                );
            }

            // Opción 1: Usar CertificateFactory (más estándar)
            try (StringReader reader = new StringReader(cleanPem);
                 java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(
                     cleanPem.getBytes(StandardCharsets.UTF_8))) {

                CertificateFactory certFactory = CertificateFactory.getInstance("X.509", BouncyCastleProvider.PROVIDER_NAME);
                X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(bais);

                PublicKey publicKey = certificate.getPublicKey();
                log.debug("Clave pública extraída exitosamente. Algoritmo: {}", publicKey.getAlgorithm());

                return publicKey;
            }

        } catch (IllegalArgumentException e) {
            log.error("Formato PEM inválido: {}", e.getMessage());
            throw new DecryptionException("Formato de certificado PEM inválido: " + e.getMessage(), e);

        } catch (Exception e) {
            log.error("Error al parsear certificado PEM: {}", e.getMessage(), e);
            throw DecryptionException.certificateParsingFailed(e);
        }
    }

    /**
     * Extrae metadata de un certificado PEM.
     *
     * @param certificatePem Certificado en formato PEM
     * @return CertificateMetadata con información del certificado
     * @throws DecryptionException si hay error al parsear
     */
    public CertificateMetadata extractMetadata(String certificatePem) {
        try {
            log.debug("Extrayendo metadata del certificado");

            String cleanPem = certificatePem.trim();

            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(
                cleanPem.getBytes(StandardCharsets.UTF_8))) {

                CertificateFactory certFactory = CertificateFactory.getInstance("X.509", BouncyCastleProvider.PROVIDER_NAME);
                X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(bais);

                // Extraer información
                String serialNumber = certificate.getSerialNumber().toString(16).toUpperCase();
                String subject = certificate.getSubjectX500Principal().getName();
                String issuer = certificate.getIssuerX500Principal().getName();
                LocalDate validFrom = convertToLocalDate(certificate.getNotBefore());
                LocalDate validTo = convertToLocalDate(certificate.getNotAfter());

                CertificateMetadata metadata = CertificateMetadata.builder()
                    .serialNumber(serialNumber)
                    .subject(subject)
                    .issuer(issuer)
                    .validFrom(validFrom)
                    .validTo(validTo)
                    .build();

                log.debug("Metadata extraída: Serial={}, Subject={}", serialNumber, subject);

                return metadata;
            }

        } catch (Exception e) {
            log.error("Error al extraer metadata del certificado: {}", e.getMessage(), e);
            throw DecryptionException.certificateParsingFailed(e);
        }
    }

    /**
     * Calcula el hash SHA-256 de un string.
     *
     * @param input String a hashear
     * @return Hash SHA-256 en formato hexadecimal
     */
    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convertir a hexadecimal
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (Exception e) {
            log.error("Error al calcular SHA-256: {}", e.getMessage(), e);
            throw new RuntimeException("Error al calcular hash SHA-256", e);
        }
    }

    /**
     * Convierte Date a LocalDate.
     *
     * @param date Date a convertir
     * @return LocalDate
     */
    private LocalDate convertToLocalDate(Date date) {
        return Instant.ofEpochMilli(date.getTime())
            .atZone(ZoneId.systemDefault())
            .toLocalDate();
    }

    /**
     * Metadata extraída de un certificado X.509.
     */
    @Data
    @Builder
    public static class CertificateMetadata {
        /**
         * Número de serie del certificado (hexadecimal).
         */
        private String serialNumber;

        /**
         * Subject del certificado (DN).
         */
        private String subject;

        /**
         * Issuer del certificado (DN).
         */
        private String issuer;

        /**
         * Fecha desde la cual el certificado es válido.
         */
        private LocalDate validFrom;

        /**
         * Fecha hasta la cual el certificado es válido.
         */
        private LocalDate validTo;
    }
}
