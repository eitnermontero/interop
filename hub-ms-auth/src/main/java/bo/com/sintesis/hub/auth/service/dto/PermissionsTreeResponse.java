package bo.com.sintesis.hub.auth.service.dto;

import java.util.List;

public record PermissionsTreeResponse(
    UserInfo user,
    List<MenuNode> menus
) {
    public record UserInfo(
        String id,
        String username,
        String email,
        String fullName,
        List<String> roles
    ) {}

    public record MenuNode(
        String code,
        String name,
        String icon,
        String route,
        List<String> actions,
        List<MenuNode> children
    ) {}
}
