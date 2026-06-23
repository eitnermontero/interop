package bo.com.sintesis.mdqr.auth.service;

import bo.com.sintesis.mdqr.auth.domain.Action;
import bo.com.sintesis.mdqr.auth.repository.ActionRepository;
import bo.com.sintesis.mdqr.auth.service.dto.ActionDto;
import bo.com.sintesis.mdqr.auth.service.dto.CreateActionRequest;
import bo.com.sintesis.mdqr.auth.service.dto.UpdateActionRequest;
import bo.com.sintesis.mdqr.auth.web.rest.errors.AdminApiException;
import bo.com.sintesis.mdqr.auth.web.rest.errors.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ActionService {

    private final ActionRepository actionRepository;

    @Transactional(readOnly = true)
    public List<ActionDto> list() {
        return actionRepository.findAll(Sort.by("code").ascending()).stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public ActionDto getById(Long id) {
        return actionRepository.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new AdminApiException(ErrorCode.ACTION_NOT_FOUND,
                "Action not found: " + id));
    }

    @Transactional
    public ActionDto create(CreateActionRequest req) {
        if (actionRepository.existsByCode(req.code())) {
            throw new AdminApiException(ErrorCode.ACTION_CONFLICT,
                "Action code already exists: " + req.code());
        }
        Action a = new Action();
        a.setCode(req.code());
        a.setName(req.name());
        a.setDescription(req.description());
        return toDto(actionRepository.save(a));
    }

    @Transactional
    public ActionDto update(Long id, UpdateActionRequest req) {
        Action a = actionRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(ErrorCode.ACTION_NOT_FOUND,
                "Action not found: " + id));
        if (req.name() != null) a.setName(req.name());
        if (req.description() != null) a.setDescription(req.description());
        return toDto(actionRepository.save(a));
    }

    @Transactional
    public void delete(Long id) {
        Action a = actionRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(ErrorCode.ACTION_NOT_FOUND,
                "Action not found: " + id));
        try {
            actionRepository.delete(a);
            actionRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new AdminApiException(ErrorCode.ACTION_IN_USE,
                "Action is referenced by existing role-permission assignments: " + a.getCode());
        }
    }

    private ActionDto toDto(Action a) {
        return new ActionDto(a.getId(), a.getCode(), a.getName(), a.getDescription());
    }
}
