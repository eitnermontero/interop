package bo.com.sintesis.mdqr.base.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

/**
 * Servicio para validación y extracción de metadata de certificados.
 * Procesa archivos PEM y extrae información crítica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateValidationService {

    /**
     * Valida y extrae metadata de un certificado PEM.
     *
     * @param pemContent Contenido PEM del certificado
     * @return Metadata extraída
     * @throws CertificateException Si el certificado es inválido
     */
    public CertificateMetadata validateAndExtractMetadata(String pemContent) throws CertificateException {
        log.debug("Validating PEM certificate");

        // Parse PEM to X509Certificate
        X509Certificate x509Cert = parsePemCertificate(pemContent);

        // Extract metadata
        CertificateMetadata metadata = new CertificateMetadata();
        metadata.setSerialNumber(x509Cert.getSerialNumber().toString(16).toLowerCase());
        metadata.setSubjectDn(x509Cert.getSubjectX500Principal().getName());
        metadata.setIssuerDn(x509Cert.getIssuerX500Principal().getName());
        metadata.setIssuerCn(extractCN(x509Cert.getIssuerX500Principal().getName()));
        metadata.setValidFrom(x509Cert.getNotBefore().toInstant());
        metadata.setValidTo(x509Cert.getNotAfter().toInstant());
        metadata.setFingerprintSha256(calculateSha256Fingerprint(x509Cert));

        // Validate dates
        validateCertificateDates(x509Cert);

        log.info("Certificate validated successfully - Serial: {}, Issuer: {}",
                 metadata.getSerialNumber(), metadata.getIssuerCn());

        return metadata;
    }

    /**
     * Parse PEM string to X509Certificate object.
     */
    public X509Certificate parsePemCertificate(String pemContent) throws CertificateException {
        if (pemContent == null || pemContent.trim().isEmpty()) {
            throw new CertificateException("PEM content is empty");
        }

        try {
            // Normalize PEM content
            String normalizedPem = normalizePemContent(pemContent);

            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream inputStream = new ByteArrayInputStream(
                normalizedPem.getBytes(StandardCharsets.UTF_8)
            );

            X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(inputStream);

            if (certificate == null) {
                throw new CertificateException("Failed to parse certificate from PEM");
            }

            return certificate;

        } catch (CertificateException e) {
            log.error("Failed to parse PEM certificate: {}", e.getMessage());
            throw new CertificateException("Invalid PEM format: " + e.getMessage(), e);
        }
    }

    /**
     * Normaliza contenido PEM (elimina espacios, valida headers).
     */
    private String normalizePemContent(String pemContent) throws CertificateException {
        String normalized = pemContent.trim();

        // Validate PEM headers
        if (!normalized.contains("-----BEGIN CERTIFICATE-----") ||
            !normalized.contains("-----END CERTIFICATE-----")) {
            throw new CertificateException("Invalid PEM format: missing BEGIN/END CERTIFICATE markers");
        }

        return normalized;
    }

    /**
     * Calcula SHA-256 fingerprint del certificado.
     */
    public String calculateSha256Fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] der = certificate.getEncoded();
            byte[] digest = md.digest(der);

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (Exception e) {
            log.error("Failed to calculate certificate fingerprint", e);
            throw new CertificateException("Failed to calculate fingerprint: " + e.getMessage(), e);
        }
    }

    /**
     * Extrae Common Name (CN) del Distinguished Name.
     */
    private String extractCN(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }

        // Parse DN to extract CN
        String[] parts = dn.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }

        return null;
    }

    /**
     * Valida que las fechas del certificado sean consistentes.
     */
    private void validateCertificateDates(X509Certificate certificate) throws CertificateException {
        Instant now = Instant.now();
        Instant validFrom = certificate.getNotBefore().toInstant();
        Instant validTo = certificate.getNotAfter().toInstant();

        if (validFrom.isAfter(validTo)) {
            throw new CertificateException("Certificate notBefore date is after notAfter date");
        }

        // Log warnings but don't reject
        if (validTo.isBefore(now)) {
            log.warn("Certificate is expired - Valid until: {}", validTo);
        }

        if (validFrom.isAfter(now)) {
            log.warn("Certificate is not yet valid - Valid from: {}", validFrom);
        }
    }

    /**
     * Verifica si un certificado está expirado.
     */
    public boolean isExpired(X509Certificate certificate) {
        return certificate.getNotAfter().toInstant().isBefore(Instant.now());
    }

    /**
     * Verifica si un certificado está por expirar (30 días o menos).
     */
    public boolean isExpiringSoon(X509Certificate certificate) {
        Instant expirationDate = certificate.getNotAfter().toInstant();
        Instant thirtyDaysFromNow = Instant.now().plusSeconds(30L * 24 * 60 * 60);
        return expirationDate.isBefore(thirtyDaysFromNow) && expirationDate.isAfter(Instant.now());
    }

    /**
     * DTO para metadata de certificado extraída.
     */
    public static class CertificateMetadata {
        private String serialNumber;
        private String subjectDn;
        private String issuerDn;
        private String issuerCn;
        private Instant validFrom;
        private Instant validTo;
        private String fingerprintSha256;

        // Getters and setters
        public String getSerialNumber() {
            return serialNumber;
        }

        public void setSerialNumber(String serialNumber) {
            this.serialNumber = serialNumber;
        }

        public String getSubjectDn() {
            return subjectDn;
        }

        public void setSubjectDn(String subjectDn) {
            this.subjectDn = subjectDn;
        }

        public String getIssuerDn() {
            return issuerDn;
        }

        public void setIssuerDn(String issuerDn) {
            this.issuerDn = issuerDn;
        }

        public String getIssuerCn() {
            return issuerCn;
        }

        public void setIssuerCn(String issuerCn) {
            this.issuerCn = issuerCn;
        }

        public Instant getValidFrom() {
            return validFrom;
        }

        public void setValidFrom(Instant validFrom) {
            this.validFrom = validFrom;
        }

        public Instant getValidTo() {
            return validTo;
        }

        public void setValidTo(Instant validTo) {
            this.validTo = validTo;
        }

        public String getFingerprintSha256() {
            return fingerprintSha256;
        }

        public void setFingerprintSha256(String fingerprintSha256) {
            this.fingerprintSha256 = fingerprintSha256;
        }
    }
}
