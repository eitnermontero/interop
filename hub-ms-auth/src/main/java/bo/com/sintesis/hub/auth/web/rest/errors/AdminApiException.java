package bo.com.sintesis.hub.auth.web.rest.errors;

import lombok.Getter;

@Getter
public class AdminApiException extends RuntimeException {

    private final ErrorCode code;

    public AdminApiException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public AdminApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public AdminApiException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }
}
