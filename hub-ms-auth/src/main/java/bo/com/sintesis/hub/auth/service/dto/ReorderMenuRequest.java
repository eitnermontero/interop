package bo.com.sintesis.hub.auth.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReorderMenuRequest(
    @NotEmpty
    @Valid
    List<Entry> items
) {
    public record Entry(
        @NotNull Long id,
        @NotNull Integer orderIndex
    ) {}
}
