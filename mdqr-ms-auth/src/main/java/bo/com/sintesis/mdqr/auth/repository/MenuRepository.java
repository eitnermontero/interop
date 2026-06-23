package bo.com.sintesis.mdqr.auth.repository;

import bo.com.sintesis.mdqr.auth.domain.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuRepository extends JpaRepository<Menu, Long> {

    Optional<Menu> findByCode(String code);

    boolean existsByCode(String code);

    List<Menu> findByParentIsNullAndIsActiveTrueOrderByOrderIndexAsc();

    List<Menu> findByParentIdAndIsActiveTrueOrderByOrderIndexAsc(Long parentId);

    List<Menu> findAllByOrderByOrderIndexAsc();
}
