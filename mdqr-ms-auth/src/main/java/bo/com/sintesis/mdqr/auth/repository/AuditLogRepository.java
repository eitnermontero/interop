package bo.com.sintesis.mdqr.auth.repository;

import bo.com.sintesis.mdqr.auth.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    @Query("SELECT DISTINCT a.eventType FROM AuditLog a ORDER BY a.eventType")
    List<String> findDistinctEventTypes();

    @Query("SELECT DISTINCT a.module FROM AuditLog a ORDER BY a.module")
    List<String> findDistinctModules();

    // Used by KeycloakEventPoller to initialize the dedup cursor on startup.
    @Query("SELECT MAX(a.eventTime) FROM AuditLog a WHERE a.serviceName = :serviceName")
    Optional<Instant> findMaxEventTimeByServiceName(String serviceName);
}
