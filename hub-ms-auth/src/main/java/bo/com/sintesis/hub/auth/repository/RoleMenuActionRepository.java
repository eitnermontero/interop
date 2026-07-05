package bo.com.sintesis.hub.auth.repository;

import bo.com.sintesis.hub.auth.domain.RoleMenuAction;
import bo.com.sintesis.hub.auth.domain.RoleMenuActionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RoleMenuActionRepository extends JpaRepository<RoleMenuAction, RoleMenuActionId> {

    /**
     * All grants for a given Keycloak role (used by the role permissions editor UI).
     */
    @Query("""
        SELECT r FROM RoleMenuAction r
        WHERE r.id.keycloakRoleName = :roleName
        """)
    List<RoleMenuAction> findByRoleName(@Param("roleName") String roleName);

    /**
     * All grants for a set of roles (used to build the permissions tree of the current user).
     * Joins menu and action to allow projecting their codes/names in the service layer.
     */
    @Query("""
        SELECT r FROM RoleMenuAction r
        JOIN FETCH r.menu m
        JOIN FETCH r.action a
        WHERE r.id.keycloakRoleName IN :roleNames
          AND r.isGranted = true
          AND m.isActive = true
        """)
    List<RoleMenuAction> findGrantedByRoles(@Param("roleNames") Collection<String> roleNames);

    /**
     * Authorization check used by @PreAuthorize("@permissionService.hasAction(...)").
     */
    @Query("""
        SELECT (COUNT(r) > 0) FROM RoleMenuAction r
        WHERE r.id.keycloakRoleName IN :roleNames
          AND r.menu.code = :menuCode
          AND r.action.code = :actionCode
          AND r.isGranted = true
        """)
    boolean existsGrant(@Param("roleNames") Collection<String> roleNames,
                        @Param("menuCode") String menuCode,
                        @Param("actionCode") String actionCode);

    @Modifying
    @Query("DELETE FROM RoleMenuAction r WHERE r.id.keycloakRoleName = :roleName")
    int deleteByRoleName(@Param("roleName") String roleName);

    /**
     * Get all distinct menu codes assigned to a role (for menu assignment UI).
     */
    @Query("""
        SELECT DISTINCT m.code FROM RoleMenuAction r
        JOIN r.menu m
        WHERE r.id.keycloakRoleName = :roleName
        """)
    List<String> findMenuCodesByRoleName(@Param("roleName") String roleName);

    /**
     * Create a menu assignment for a role (with default action: READ).
     */
    @Modifying
    @Query(value = """
        INSERT INTO admin.role_menu_action (keycloak_role_name, menu_id, action_id, is_granted, created_by, created_date)
        SELECT :roleName, m.id, a.id, true, 'SYSTEM', NOW() FROM admin.menu m, admin.action a
        WHERE m.code = :menuCode AND a.code = 'READ'
        """, nativeQuery = true)
    void createMenuAssignment(@Param("roleName") String roleName, @Param("menuCode") String menuCode);
}
