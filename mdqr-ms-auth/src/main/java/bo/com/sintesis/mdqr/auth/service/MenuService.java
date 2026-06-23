package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.domain.Menu;
import bo.com.sintesis.mdqr.auth.repository.MenuRepository;
import bo.com.sintesis.mdqr.auth.service.dto.CreateMenuRequest;
import bo.com.sintesis.mdqr.auth.service.dto.MenuDto;
import bo.com.sintesis.mdqr.auth.service.dto.ReorderMenuRequest;
import bo.com.sintesis.mdqr.auth.service.dto.UpdateMenuRequest;
import bo.com.sintesis.mdqr.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.mdqr.auth.web.rest.errors.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public List<MenuDto> tree() {
        List<Menu> all = menuRepository.findAllByOrderByOrderIndexAsc();
        Map<Long, List<MenuDto>> childrenByParent = new HashMap<>();
        for (Menu m : all) {
            Long parentId = m.getParent() == null ? null : m.getParent().getId();
            childrenByParent
                .computeIfAbsent(parentId, k -> new java.util.ArrayList<>())
                .add(toDto(m, List.of()));
        }
        // rebuild with children attached, sorted by orderIndex
        return buildTree(null, childrenByParent);
    }

    @Transactional(readOnly = true)
    public MenuDto getById(Long id) {
        Menu m = menuRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(ErrorCode.MENU_NOT_FOUND, "Menu not found: " + id));
        return toDto(m, List.of());
    }

    @Transactional
    public MenuDto create(CreateMenuRequest req) {
        if (menuRepository.existsByCode(req.code())) {
            throw new AdminApiException(ErrorCode.MENU_CONFLICT, "Menu code already exists: " + req.code());
        }

        Menu m = new Menu();
        m.setCode(req.code());
        m.setName(req.name());
        m.setIcon(req.icon());
        m.setRoute(req.route());
        m.setOrderIndex(req.orderIndex() == null ? 0 : req.orderIndex());
        m.setIsActive(req.isActive() == null ? Boolean.TRUE : req.isActive());
        if (req.parentId() != null) {
            m.setParent(resolveParent(req.parentId(), null));
        }
        return toDto(menuRepository.save(m), List.of());
    }

    @Transactional
    public MenuDto update(Long id, UpdateMenuRequest req) {
        Menu m = menuRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(ErrorCode.MENU_NOT_FOUND, "Menu not found: " + id));

        if (req.name() != null) m.setName(req.name());
        if (req.icon() != null) m.setIcon(req.icon());
        if (req.route() != null) m.setRoute(req.route());
        if (req.orderIndex() != null) m.setOrderIndex(req.orderIndex());
        if (req.isActive() != null) m.setIsActive(req.isActive());
        if (req.parentId() != null) {
            m.setParent(resolveParent(req.parentId(), id));
        }
        return toDto(menuRepository.save(m), List.of());
    }

    @Transactional
    public void delete(Long id) {
        Menu m = menuRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(ErrorCode.MENU_NOT_FOUND, "Menu not found: " + id));
        if (!menuRepository.findByParentIdAndIsActiveTrueOrderByOrderIndexAsc(id).isEmpty()) {
            throw new AdminApiException(ErrorCode.MENU_HAS_CHILDREN,
                "Cannot delete menu with active children: " + m.getCode());
        }
        try {
            menuRepository.delete(m);
            menuRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new AdminApiException(ErrorCode.MENU_IN_USE,
                "Menu is referenced by existing role-permission assignments: " + m.getCode());
        }
    }

    @Transactional
    public void reorder(ReorderMenuRequest req) {
        for (var entry : req.items()) {
            Menu m = menuRepository.findById(entry.id())
                .orElseThrow(() -> new AdminApiException(ErrorCode.MENU_NOT_FOUND,
                    "Menu not found: " + entry.id()));
            m.setOrderIndex(entry.orderIndex());
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Menu resolveParent(Long parentId, Long selfId) {
        if (selfId != null && parentId.equals(selfId)) {
            throw new AdminApiException(ErrorCode.VALIDATION_ERROR, "A menu cannot be its own parent");
        }
        return menuRepository.findById(parentId)
            .orElseThrow(() -> new AdminApiException(ErrorCode.MENU_NOT_FOUND,
                "Parent menu not found: " + parentId));
    }

    private List<MenuDto> buildTree(Long parentId, Map<Long, List<MenuDto>> byParent) {
        List<MenuDto> level = byParent.getOrDefault(parentId, List.of());
        return level.stream()
            .sorted(Comparator.comparingInt(d -> d.orderIndex() == null ? 0 : d.orderIndex()))
            .map(d -> new MenuDto(
                d.id(), d.code(), d.name(), d.icon(), d.route(),
                d.parentId(), d.orderIndex(), d.isActive(),
                buildTree(d.id(), byParent)
            ))
            .toList();
    }

    private MenuDto toDto(Menu m, List<MenuDto> children) {
        return new MenuDto(
            m.getId(),
            m.getCode(),
            m.getName(),
            m.getIcon(),
            m.getRoute(),
            m.getParent() == null ? null : m.getParent().getId(),
            m.getOrderIndex(),
            m.getIsActive(),
            children
        );
    }
}
