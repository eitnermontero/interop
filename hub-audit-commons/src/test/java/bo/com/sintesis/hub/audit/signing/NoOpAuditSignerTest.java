package bo.com.sintesis.hub.audit.signing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpAuditSignerTest {

    @Test
    void shouldReturnEmptySignatureAndZeroVersion() {
        AuditSigner signer = new NoOpAuditSigner();
        SignResult result = signer.sign("a".repeat(64));
        assertThat(result).isNotNull();
        assertThat(result.signature()).isEmpty();
        assertThat(result.keyVersion()).isZero();
    }

    @Test
    void shouldReturnConsistentResultForAnyInput() {
        AuditSigner signer = new NoOpAuditSigner();
        SignResult r1 = signer.sign("hash-cualquiera");
        SignResult r2 = signer.sign("otro-hash");
        assertThat(r1.signature()).isEmpty();
        assertThat(r2.signature()).isEmpty();
        assertThat(r1.keyVersion()).isEqualTo(r2.keyVersion()).isZero();
    }
}
