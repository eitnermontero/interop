package bo.com.sintesis.mdqr.audit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStatusResolverTest {

    // --- fromReturn ---

    @Test
    void fromReturn_201_created() {
        Object result = ResponseEntity.created(java.net.URI.create("/foo/1")).build();
        assertThat(HttpStatusResolver.fromReturn(result)).isEqualTo(201);
    }

    @Test
    void fromReturn_202_accepted() {
        Object result = ResponseEntity.accepted().build();
        assertThat(HttpStatusResolver.fromReturn(result)).isEqualTo(202);
    }

    @Test
    void fromReturn_nonResponseEntity_defaults_200() {
        assertThat(HttpStatusResolver.fromReturn("plain string result")).isEqualTo(200);
        assertThat(HttpStatusResolver.fromReturn(null)).isEqualTo(200);
    }

    // --- fromException ---

    @Test
    void fromException_annotated_400() {
        assertThat(HttpStatusResolver.fromException(new BadRequestException())).isEqualTo(400);
    }

    @Test
    void fromException_no_annotation_defaults_500() {
        assertThat(HttpStatusResolver.fromException(new RuntimeException("oops"))).isEqualTo(500);
    }

    @Test
    void fromException_annotation_inherited_from_superclass() {
        assertThat(HttpStatusResolver.fromException(new SubBadRequestException())).isEqualTo(400);
    }

    // --- Helper exceptions ---

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class BadRequestException extends RuntimeException {
        BadRequestException() { super("bad"); }
    }

    static class SubBadRequestException extends BadRequestException {
        SubBadRequestException() { super(); }
    }
}
