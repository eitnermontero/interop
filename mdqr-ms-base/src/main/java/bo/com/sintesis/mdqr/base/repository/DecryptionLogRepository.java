package bo.com.sintesis.mdqr.base.repository;

import bo.com.sintesis.mdqr.base.domain.DecryptionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DecryptionLogRepository extends JpaRepository<DecryptionLog, Long>, JpaSpecificationExecutor<DecryptionLog> {

    Optional<DecryptionLog> findByLogId(String logId);

    List<DecryptionLog> findByKeycloakClientIdOrderByCreatedDateDesc(String keycloakClientId);

    Page<DecryptionLog> findByKeycloakClientIdOrderByCreatedDateDesc(String keycloakClientId, Pageable pageable);

    long countByStatus(String status);
}
