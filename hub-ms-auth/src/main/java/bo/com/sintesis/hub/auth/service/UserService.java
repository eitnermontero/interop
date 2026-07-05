package bo.com.sintesis.hub.auth.service;

import bo.com.sintesis.hub.auth.service.dto.CreateUserRequest;
import bo.com.sintesis.hub.auth.service.dto.PageResponse;
import bo.com.sintesis.hub.auth.service.dto.UpdateUserRequest;
import bo.com.sintesis.hub.auth.service.dto.UserDto;
import bo.com.sintesis.hub.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.hub.auth.web.rest.errors.ErrorCode;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final RealmResource realm;

    public PageResponse<UserDto> list(String search, Boolean enabled, int page, int size) {
        int first = page * size;
        var query = realm.users();

        long total = (search == null || search.isBlank())
            ? query.count()
            : query.count(search);

        var reps = (search == null || search.isBlank())
            ? query.list(first, size)
            : query.search(search, first, size);

        var content = reps.stream()
            .filter(u -> enabled == null || enabled.equals(u.isEnabled()))
            .map(this::toDto)
            .toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        boolean last = (page + 1) >= totalPages;
        return new PageResponse<>(content, page, size, total, totalPages, last);
    }

    public UserDto getById(String userId) {
        return toDto(getUserResource(userId).toRepresentation());
    }

    public UserDto create(CreateUserRequest req) {
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(req.username());
        rep.setEmail(req.email());
        rep.setFirstName(req.firstName());
        rep.setLastName(req.lastName());
        rep.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        rep.setEmailVerified(req.emailVerified() == null ? Boolean.FALSE : req.emailVerified());
        if (req.attributes() != null) {
            rep.setAttributes(req.attributes());
        }

        String createdId;
        try (Response response = realm.users().create(rep)) {
            if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new AdminApiException(ErrorCode.USER_CONFLICT);
            }
            if (response.getStatus() >= 400) {
                throw new AdminApiException(ErrorCode.KEYCLOAK_UPSTREAM_ERROR,
                    "Failed to create user (status " + response.getStatus() + ")");
            }
            createdId = extractIdFromLocation(response);
        }

        if (req.password() != null && !req.password().isBlank()) {
            setPassword(createdId, req.password(),
                req.temporaryPassword() != null && req.temporaryPassword());
        }
        if (req.roles() != null && !req.roles().isEmpty()) {
            replaceRealmRoles(createdId, req.roles());
        }
        return getById(createdId);
    }

    public UserDto update(String userId, UpdateUserRequest req) {
        var userResource = getUserResource(userId);
        var rep = userResource.toRepresentation();

        if (req.email() != null) rep.setEmail(req.email());
        if (req.firstName() != null) rep.setFirstName(req.firstName());
        if (req.lastName() != null) rep.setLastName(req.lastName());
        if (req.enabled() != null) rep.setEnabled(req.enabled());
        if (req.emailVerified() != null) rep.setEmailVerified(req.emailVerified());
        if (req.attributes() != null) rep.setAttributes(req.attributes());

        userResource.update(rep);
        return toDto(userResource.toRepresentation());
    }

    public void delete(String userId) {
        getUserResource(userId).remove();
    }

    public void setStatus(String userId, boolean enabled) {
        var userResource = getUserResource(userId);
        var rep = userResource.toRepresentation();
        rep.setEnabled(enabled);
        userResource.update(rep);
    }

    public void setPassword(String userId, String password, boolean temporary) {
        var cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(temporary);
        getUserResource(userId).resetPassword(cred);
    }

    public List<String> getRealmRoles(String userId) {
        return getUserResource(userId).roles().realmLevel().listAll().stream()
            .map(RoleRepresentation::getName)
            .sorted()
            .toList();
    }

    public void replaceRealmRoles(String userId, List<String> roleNames) {
        var roleScope = getUserResource(userId).roles().realmLevel();
        var current = roleScope.listAll();
        if (!current.isEmpty()) {
            roleScope.remove(current);
        }
        if (roleNames == null || roleNames.isEmpty()) return;

        var available = realm.roles().list().stream()
            .collect(java.util.stream.Collectors.toMap(RoleRepresentation::getName, r -> r));

        List<RoleRepresentation> toAdd = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : roleNames) {
            var role = available.get(name);
            if (role == null) {
                missing.add(name);
            } else {
                toAdd.add(role);
            }
        }
        if (!missing.isEmpty()) {
            throw new AdminApiException(ErrorCode.VALIDATION_ERROR,
                "Unknown realm role(s): " + String.join(", ", missing));
        }
        roleScope.add(toAdd);
    }

    public void sendResetPasswordEmail(String userId) {
        getUserResource(userId).executeActionsEmail(List.of("UPDATE_PASSWORD"));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private UserResource getUserResource(String userId) {
        return realm.users().get(userId);
    }

    private UserDto toDto(UserRepresentation r) {
        return new UserDto(
            r.getId(),
            r.getUsername(),
            r.getEmail(),
            r.getFirstName(),
            r.getLastName(),
            r.isEnabled(),
            r.isEmailVerified(),
            r.getCreatedTimestamp(),
            r.getAttributes() == null ? Map.of() : r.getAttributes()
        );
    }

    private String extractIdFromLocation(Response response) {
        return Optional.ofNullable(response.getLocation())
            .map(java.net.URI::getPath)
            .map(path -> path.substring(path.lastIndexOf('/') + 1))
            .orElseThrow(() -> new AdminApiException(ErrorCode.KEYCLOAK_UPSTREAM_ERROR,
                "Could not determine created user ID from Keycloak response"));
    }
}
