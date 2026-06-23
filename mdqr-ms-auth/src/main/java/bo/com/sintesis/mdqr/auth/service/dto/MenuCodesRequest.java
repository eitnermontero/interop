package bo.com.sintesis.mdqr.auth.service.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MenuCodesRequest(
    @NotNull List<String> menuCodes
) {}
