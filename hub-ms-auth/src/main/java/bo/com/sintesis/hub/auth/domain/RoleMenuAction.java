package bo.com.sintesis.hub.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "role_menu_action")
@Getter
@Setter
public class RoleMenuAction extends AbstractAuditingEntity<RoleMenuActionId> {

    @EmbeddedId
    private RoleMenuActionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("menuId")
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("actionId")
    @JoinColumn(name = "action_id", nullable = false)
    private Action action;

    @Column(name = "is_granted", nullable = false)
    private Boolean isGranted = true;
}
