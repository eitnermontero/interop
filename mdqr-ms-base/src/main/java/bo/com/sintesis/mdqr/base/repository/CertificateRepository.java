package bo.com.sintesis.mdqr.base.repository;

import bo.com.sintesis.mdqr.base.domain.Certificate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio para entidad Certificate.
 * Maneja certificados públicos de bancos.
 */
@Repository
public interface CertificateRepository extends JpaRepository<Certificate, Long>, JpaSpecificationExecutor<Certificate> {

    // ============================================================
    // Queries para desencriptación de QRs
    // ============================================================

    /**
     * Busca certificado por serial number (CRÍTICO para desencriptar QRs).
     * Retorna el certificado actual y activo.
     */
    @Query("SELECT c FROM Certificate c " +
           "WHERE c.serialNumber = :serialNumber " +
           "AND c.isCurrentVersion = true " +
           "AND c.isActive = true " +
           "AND c.isRevoked = false")
    Optional<Certificate> findBySerialNumberAndIsCurrentVersionTrue(@Param("serialNumber") String serialNumber);

    /**
     * Busca certificado por serial sin filtros (para ver histórico).
     */
    Optional<Certificate> findBySerialNumber(String serialNumber);

    // ============================================================
    // Queries para validación de duplicados
    // ============================================================

    boolean existsBySerialNumber(String serialNumber);

    boolean existsByFingerprintSha256(String fingerprintSha256);

    Optional<Certificate> findByFingerprintSha256(String fingerprintSha256);

    // ============================================================
    // Queries para listado y filtros
    // ============================================================

    List<Certificate> findByEntityId(String entityId);

    Page<Certificate> findByEntityId(String entityId, Pageable pageable);

    Page<Certificate> findByStatus(Certificate.CertificateStatus status, Pageable pageable);

    Page<Certificate> findByEntityIdAndStatus(String entityId, Certificate.CertificateStatus status, Pageable pageable);

    /**
     * Certificados activos (current version, no revocados)
     */
    @Query("SELECT c FROM Certificate c " +
           "WHERE c.isActive = true " +
           "AND c.isRevoked = false " +
           "AND c.isCurrentVersion = true " +
           "ORDER BY c.validTo DESC")
    List<Certificate> findAllActive();

    Page<Certificate> findByIsCurrentVersionTrueAndIsRevokedFalse(Pageable pageable);

    // ============================================================
    // Queries para certificados por expirar
    // ============================================================

    /**
     * Certificados que expiran dentro de X días
     */
    @Query("SELECT c FROM Certificate c " +
           "WHERE c.isCurrentVersion = true " +
           "AND c.isActive = true " +
           "AND c.isRevoked = false " +
           "AND c.validTo > :now " +
           "AND c.validTo <= :expirationDate " +
           "ORDER BY c.validTo ASC")
    List<Certificate> findExpiringBefore(@Param("now") Instant now, @Param("expirationDate") Instant expirationDate);

    /**
     * Certificados expirados
     */
    @Query("SELECT c FROM Certificate c " +
           "WHERE c.isCurrentVersion = true " +
           "AND c.validTo < :now " +
           "AND c.status != 'REVOKED' " +
           "ORDER BY c.validTo DESC")
    List<Certificate> findExpired(@Param("now") Instant now);

    // ============================================================
    // Queries para métricas del dashboard
    // ============================================================

    @Query("SELECT COUNT(c) FROM Certificate c " +
           "WHERE c.isCurrentVersion = true " +
           "AND c.isActive = true " +
           "AND c.status = :status")
    long countByStatus(@Param("status") Certificate.CertificateStatus status);

    @Query("SELECT c.entityId, COUNT(c) FROM Certificate c " +
           "WHERE c.isCurrentVersion = true " +
           "AND c.isActive = true " +
           "GROUP BY c.entityId")
    List<Object[]> countByEntity();
}
