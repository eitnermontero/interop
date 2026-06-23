package bo.com.sintesis.mdqr.auth.repository;

import bo.com.sintesis.mdqr.auth.domain.Action;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActionRepository extends JpaRepository<Action, Long> {

    Optional<Action> findByCode(String code);

    boolean existsByCode(String code);
}
