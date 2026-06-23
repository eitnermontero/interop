package bo.com.sintesis.mdqr.base.repository;

import bo.com.sintesis.mdqr.base.domain.CertificateAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repositorio para auditoría de certificados.
 * Registra TODAS las operaciones incluyendo desencriptación de QRs.
 */
@Repository
public interface CertificateAuditLogRepository extends JpaRepository<CertificateAuditLog, Long> {

    // ============================================================
    // Queries para auditoría de certificados específicos
    // ============================================================

    /**
     * Historial completo de un certificado (por ID)
     */
    Page<CertificateAuditLog> findByCertificateIdOrderByTimestampDesc(Long certificateId, Pageable pageable);

    /**
     * Historial por serial number (incluye todas las versiones)
     */
    Page<CertificateAuditLog> findBySerialNumberOrderByTimestampDesc(String serialNumber, Pageable pageable);

    // ============================================================
    // Queries críticas para desencriptación de QRs
    // ============================================================

    /**
     * CRÍTICO: Auditoría de desencriptación de QRs para pagos.
     * Permite rastrear qué certificado desencriptó qué QR y cuándo.
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.action = 'DECRYPT_QR' " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findAllDecryptQrActions(Pageable pageable);

    /**
     * Auditoría de desencriptación por certificado
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.certificateId = :certificateId " +
           "AND a.action = 'DECRYPT_QR' " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findDecryptQrActionsByCertificate(@Param("certificateId") Long certificateId, Pageable pageable);

    /**
     * Auditoría de desencriptación por serial
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.serialNumber = :serialNumber " +
           "AND a.action = 'DECRYPT_QR' " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findDecryptQrActionsBySerial(@Param("serialNumber") String serialNumber, Pageable pageable);

    /**
     * Contar desencriptaciones de QRs por certificado (métrica de uso)
     */
    @Query("SELECT COUNT(a) FROM CertificateAuditLog a " +
           "WHERE a.certificateId = :certificateId " +
           "AND a.action = 'DECRYPT_QR' " +
           "AND a.success = true")
    long countSuccessfulDecryptionsByCertificate(@Param("certificateId") Long certificateId);

    // ============================================================
    // Queries por tipo de acción
    // ============================================================

    /**
     * Historial de un tipo de acción específico
     */
    Page<CertificateAuditLog> findByActionOrderByTimestampDesc(CertificateAuditLog.AuditAction action, Pageable pageable);

    /**
     * Acciones de un usuario específico
     */
    Page<CertificateAuditLog> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    // ============================================================
    // Queries por rango de tiempo
    // ============================================================

    /**
     * Auditoría en rango de fechas
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.timestamp BETWEEN :startDate AND :endDate " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findByTimestampBetween(
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate,
        Pageable pageable
    );

    /**
     * Auditoría de desencriptaciones en rango de fechas (para reportes de pagos)
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.action = 'DECRYPT_QR' " +
           "AND a.timestamp BETWEEN :startDate AND :endDate " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findDecryptQrActionsBetween(
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate,
        Pageable pageable
    );

    // ============================================================
    // Queries por éxito/fallo
    // ============================================================

    /**
     * Operaciones fallidas (para troubleshooting)
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.success = false " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findFailedActions(Pageable pageable);

    /**
     * Desencriptaciones fallidas (para detectar problemas con certificados)
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.action = 'DECRYPT_QR' " +
           "AND a.success = false " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findFailedDecryptQrActions(Pageable pageable);

    // ============================================================
    // Queries para métricas y dashboard
    // ============================================================

    /**
     * Contar operaciones por tipo de acción en rango de tiempo
     */
    @Query("SELECT a.action, COUNT(a) FROM CertificateAuditLog a " +
           "WHERE a.timestamp >= :since " +
           "GROUP BY a.action")
    List<Object[]> countActionsSince(@Param("since") Instant since);

    /**
     * Contar desencriptaciones exitosas por día (para gráficas)
     */
    @Query("SELECT CAST(a.timestamp AS DATE), COUNT(a) FROM CertificateAuditLog a " +
           "WHERE a.action = 'DECRYPT_QR' " +
           "AND a.success = true " +
           "AND a.timestamp >= :since " +
           "GROUP BY CAST(a.timestamp AS DATE) " +
           "ORDER BY CAST(a.timestamp AS DATE) DESC")
    List<Object[]> countSuccessfulDecryptionsByDay(@Param("since") Instant since);

    /**
     * Usuarios más activos
     */
    @Query("SELECT a.userId, COUNT(a) FROM CertificateAuditLog a " +
           "WHERE a.timestamp >= :since " +
           "GROUP BY a.userId " +
           "ORDER BY COUNT(a) DESC")
    List<Object[]> findMostActiveUsers(@Param("since") Instant since);

    // ============================================================
    // Queries por entidad/banco
    // ============================================================

    /**
     * Auditoría de operaciones filtradas por entidad bancaria
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.entityIdRequest = :entityId " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findByEntityId(@Param("entityId") String entityId, Pageable pageable);

    /**
     * Desencriptaciones por entidad bancaria
     */
    @Query("SELECT a FROM CertificateAuditLog a " +
           "WHERE a.entityIdRequest = :entityId " +
           "AND a.action = 'DECRYPT_QR' " +
           "ORDER BY a.timestamp DESC")
    Page<CertificateAuditLog> findDecryptQrActionsByEntityId(@Param("entityId") String entityId, Pageable pageable);
}
