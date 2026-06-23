package bo.com.sintesis.mdqr.base.repository;

import bo.com.sintesis.mdqr.base.domain.FinancialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinancialEntityRepository extends JpaRepository<FinancialEntity, String> {

    List<FinancialEntity> findByEntityGroupOrderByParticipantAsc(String entityGroup);

    boolean existsByCode(String code);
}
