package bo.com.sintesis.mdqr.base.repository;

import bo.com.sintesis.mdqr.base.domain.CertificateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para entidad CertificateVersion.
 * Maneja historial de versiones de certificados.
 */
@Repository
public interface CertificateVersionRepository extends JpaRepository<CertificateVersion, Long> {

    /**
     * Busca todas las versiones de un certificado, ordenadas por versión descendente.
     */
    List<CertificateVersion> findByCertificateIdOrderByVersionNumberDesc(Long certificateId);

    /**
     * Busca una versión específica de un certificado.
     */
    Optional<CertificateVersion> findByCertificateIdAndVersionNumber(Long certificateId, Integer versionNumber);

    /**
     * Busca la última versión de un certificado.
     */
    @Query("SELECT v FROM CertificateVersion v " +
           "WHERE v.certificateId = :certificateId " +
           "ORDER BY v.versionNumber DESC")
    Optional<CertificateVersion> findLatestVersion(@Param("certificateId") Long certificateId);

    /**
     * Cuenta cuántas versiones tiene un certificado.
     */
    long countByCertificateId(Long certificateId);
}
