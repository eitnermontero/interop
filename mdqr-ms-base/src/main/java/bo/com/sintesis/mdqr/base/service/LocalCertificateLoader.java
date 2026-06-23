package bo.com.sintesis.mdqr.base.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio para cargar certificados públicos directamente desde archivos .crt
 * SIN necesidad de JKS.
 *
 * Ventajas:
 * - Más simple (no necesita JKS, keytool, Go API)
 * - Más flexible (agregar certificado = copiar archivo)
 * - Más rápido (lectura directa, sin serialización JKS)
 * - Organizable en carpetas por banco/entidad
 *
 * Funcionalidad:
 * - Escanea directorio de certificados al inicio
 * - Indexa por: serial, alias (nombre archivo), SHA-1, SHA-256
 * - Permite búsqueda multi-criterio
 * - Recarga automática si detecta cambios (opcional)
 */
@Service
@Slf4j
public class LocalCertificateLoader {

    private final String certificatesPath;
    private final CryptoService cryptoService;

    // Índices para búsqueda rápida
    private final Map<String, CertificateInfo> bySerial = new ConcurrentHashMap<>();
    private final Map<String, CertificateInfo> byAlias = new ConcurrentHashMap<>();
    private final Map<String, CertificateInfo> bySha1 = new ConcurrentHashMap<>();
    private final Map<String, CertificateInfo> bySha256 = new ConcurrentHashMap<>();

    public LocalCertificateLoader(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
        // Path configurable via application.yml
        this.certificatesPath = System.getProperty("certificates.path", "certificates");
    }

    /**
     * Carga todos los certificados al iniciar la aplicación.
     */
    @PostConstruct
    public void loadCertificates() {
        log.info("Iniciando carga de certificados desde: {}", certificatesPath);

        Path certDir = Paths.get(certificatesPath);

        if (!Files.exists(certDir)) {
            log.warn("Directorio de certificados no existe: {}. Creándolo...", certificatesPath);
            try {
                Files.createDirectories(certDir);
            } catch (IOException e) {
                log.error("No se pudo crear directorio de certificados", e);
                return;
            }
        }

        try {
            // Buscar recursivamente todos los archivos .crt, .pem, .cer
            Files.walk(certDir)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    return name.endsWith(".crt") || name.endsWith(".pem") || name.endsWith(".cer");
                })
                .forEach(this::loadCertificate);

            log.info("Certificados cargados exitosamente:");
            log.info("  - Por serial: {} entradas", bySerial.size());
            log.info("  - Por alias: {} entradas", byAlias.size());
            log.info("  - Por SHA-1: {} entradas", bySha1.size());
            log.info("  - Por SHA-256: {} entradas", bySha256.size());

        } catch (IOException e) {
            log.error("Error al escanear directorio de certificados", e);
        }
    }

    /**
     * Carga un certificado individual desde un archivo.
     */
    private void loadCertificate(Path filePath) {
        try {
            log.debug("Cargando certificado: {}", filePath);

            // Leer el archivo como String (PEM)
            String pemContent = Files.readString(filePath);

            // Parsear certificado X.509
            X509Certificate cert = parseCertificate(pemContent);

            // Extraer metadata
            String serial = cert.getSerialNumber().toString(16).toLowerCase();
            String alias = extractAlias(filePath);
            String subject = cert.getSubjectX500Principal().getName();
            String issuer = cert.getIssuerX500Principal().getName();

            // Calcular fingerprints
            String sha1 = calculateFingerprint(cert, "SHA-1");
            String sha256 = calculateFingerprint(cert, "SHA-256");

            // Crear objeto de información
            CertificateInfo info = CertificateInfo.builder()
                .filePath(filePath.toString())
                .pemContent(pemContent)
                .serial(serial)
                .alias(alias)
                .subject(subject)
                .issuer(issuer)
                .validFrom(cert.getNotBefore().toInstant())
                .validTo(cert.getNotAfter().toInstant())
                .sha1(sha1)
                .sha256(sha256)
                .certificate(cert)
                .build();

            // Indexar por múltiples criterios
            bySerial.put(serial, info);
            byAlias.put(alias, info);
            bySha1.put(sha1.replace(":", "").toLowerCase(), info);
            bySha256.put(sha256.replace(":", "").toLowerCase(), info);

            log.info("✓ Certificado cargado: alias={}, serial={}, subject={}",
                alias, serial, subject);

        } catch (Exception e) {
            log.error("Error al cargar certificado {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Extrae el alias desde el nombre del archivo.
     * Ejemplo: "69e6b38b.crt" → "69e6b38b"
     *          "bancosol/cert_2025.crt" → "cert_2025"
     */
    private String extractAlias(Path filePath) {
        String fileName = filePath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * Parsea un certificado PEM a X509Certificate.
     */
    private X509Certificate parseCertificate(String pemContent) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
            new java.io.ByteArrayInputStream(pemContent.getBytes())
        );
    }

    /**
     * Calcula fingerprint SHA-1 o SHA-256 del certificado.
     */
    private String calculateFingerprint(X509Certificate cert, String algorithm) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(cert.getEncoded());

            // Convertir a formato hex con ":"
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(":");
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Error calculando fingerprint {}", algorithm, e);
            return "";
        }
    }

    /**
     * Busca un certificado por cualquier criterio (serial, alias, SHA-1, SHA-256).
     * Implementa búsqueda multi-nivel automática.
     *
     * @param code Código de búsqueda (serial, alias, SHA-1, o SHA-256)
     * @return PEM del certificado
     * @throws bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException si no se encuentra
     */
    public String getCertificatePem(String code) {
        log.debug("Buscando certificado por código: {}", code);

        String normalizedCode = code.toLowerCase().replace(":", "");

        // Nivel 1: Búsqueda por serial
        CertificateInfo info = bySerial.get(normalizedCode);
        if (info != null) {
            log.debug("✓ Encontrado por serial: {}", code);
            return info.getPemContent();
        }

        // Nivel 2: Búsqueda por alias
        info = byAlias.get(normalizedCode);
        if (info != null) {
            log.debug("✓ Encontrado por alias: {}", code);
            return info.getPemContent();
        }

        // Nivel 3: Búsqueda por SHA-1
        info = bySha1.get(normalizedCode);
        if (info != null) {
            log.debug("✓ Encontrado por SHA-1: {}", code);
            return info.getPemContent();
        }

        // Nivel 4: Búsqueda por SHA-256
        info = bySha256.get(normalizedCode);
        if (info != null) {
            log.debug("✓ Encontrado por SHA-256: {}", code);
            return info.getPemContent();
        }

        log.warn("Certificado no encontrado: {}", code);
        throw bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException.forAlias(code);
    }

    /**
     * Lista todos los certificados cargados.
     */
    public List<CertificateInfo> listAllCertificates() {
        return new ArrayList<>(bySerial.values());
    }

    /**
     * Obtiene información de un certificado.
     */
    public Optional<CertificateInfo> getCertificateInfo(String code) {
        String normalizedCode = code.toLowerCase().replace(":", "");

        CertificateInfo info = bySerial.get(normalizedCode);
        if (info != null) return Optional.of(info);

        info = byAlias.get(normalizedCode);
        if (info != null) return Optional.of(info);

        info = bySha1.get(normalizedCode);
        if (info != null) return Optional.of(info);

        info = bySha256.get(normalizedCode);
        return Optional.ofNullable(info);
    }

    /**
     * Recarga todos los certificados (útil si se agregan nuevos archivos).
     */
    public void reloadCertificates() {
        log.info("Recargando certificados...");
        bySerial.clear();
        byAlias.clear();
        bySha1.clear();
        bySha256.clear();
        loadCertificates();
    }

    // ═════════════════════════════════════════════════════════════════════
    // DTO para información del certificado
    // ═════════════════════════════════════════════════════════════════════

    @lombok.Data
    @lombok.Builder
    public static class CertificateInfo {
        private String filePath;
        private String pemContent;
        private String serial;
        private String alias;
        private String subject;
        private String issuer;
        private java.time.Instant validFrom;
        private java.time.Instant validTo;
        private String sha1;
        private String sha256;
        private X509Certificate certificate;

        public boolean isExpired() {
            return java.time.Instant.now().isAfter(validTo);
        }

        public boolean isExpiringSoon(int days) {
            java.time.Instant threshold = java.time.Instant.now().plus(java.time.Duration.ofDays(days));
            return validTo.isBefore(threshold);
        }
    }
}
