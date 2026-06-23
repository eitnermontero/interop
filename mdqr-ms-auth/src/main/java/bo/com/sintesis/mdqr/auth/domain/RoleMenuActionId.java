package bo.com.sintesis.mdqr.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RoleMenuActionId implements Serializable {

    @Column(name = "keycloak_role_name", length = 100, nullable = false)
    private String keycloakRoleName;

    @Column(name = "menu_id", nullable = false)
    private Long menuId;

    @Column(name = "action_id", nullable = false)
    private Long actionId;
}
