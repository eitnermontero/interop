package bo.com.sintesis.hub.auth.service.dto;

import java.util.List;

public record RolePermissionsResponse(
    String roleName,
    List<MenuPermission> permissions
) {
    public record MenuPermission(
        String menuCode,
        String menuName,
        List<String> actions
    ) {}
}
