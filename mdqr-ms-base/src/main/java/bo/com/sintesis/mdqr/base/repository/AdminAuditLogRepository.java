package bo.com.sintesis.mdqr.base.repository;

import bo.com.sintesis.mdqr.base.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long>, JpaSpecificationExecutor<AdminAuditLog> {

    List<AdminAuditLog> findByKeycloakUserIdOrderByCreatedAtDesc(String keycloakUserId);

    List<AdminAuditLog> findByActionOrderByCreatedAtDesc(String action);
}
