package bo.com.sintesis.mdqr.audit.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainHashCalculatorTest {

    private ChainHashCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ChainHashCalculator();
    }

    @Test
    void shouldCalculateDeterministicChainHash() {
        Instant ts = Instant.parse("2026-06-22T10:00:00Z");
        ChainHashEntry entry = new ChainHashEntry(
                "partner-unilink",
                "a".repeat(64),
                "b".repeat(64),
                ts,
                "c".repeat(64));
        String hash1 = calculator.calculate(entry);
        String hash2 = calculator.calculate(entry);
        assertThat(hash1)
                .isEqualTo(hash2)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void shouldUseGenesisHashForFirstEntry() {
        Instant ts = Instant.parse("2026-06-22T10:00:00Z");
        ChainHashEntry entry = new ChainHashEntry(
                "nuevo-partner",
                "d".repeat(64),
                "e".repeat(64),
                ts,
                ChainHashCalculator.GENESIS_PREV_HASH);
        String result = calculator.calculate(entry);
        assertThat(result)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void shouldProduceDifferentChainHashForDifferentPartners() {
        Instant ts = Instant.parse("2026-06-22T10:00:00Z");
        String reqHash = "f".repeat(64);
        String resHash = "0".repeat(64);
        String prevHash = ChainHashCalculator.GENESIS_PREV_HASH;

        ChainHashEntry entryA = new ChainHashEntry("partner-A", reqHash, resHash, ts, prevHash);
        ChainHashEntry entryB = new ChainHashEntry("partner-B", reqHash, resHash, ts, prevHash);

        String hashA = calculator.calculate(entryA);
        String hashB = calculator.calculate(entryB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void shouldGenesisHashBe64HexChars() {
        assertThat(ChainHashCalculator.GENESIS_PREV_HASH)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void shouldGenesisHashMatchExpectedValue() {
        // Verificar que el valor genesis es el SHA-256 de "HUB-INTEROP-GENESIS-MDQR-V1"
        assertThat(ChainHashCalculator.GENESIS_PREV_HASH)
                .isEqualTo("5fd411223e229226d47868c9e03b9436ab6945a13db493fdfc8e8b7a90860e33");
    }

    @Test
    void shouldProduceDifferentHashWhenTimestampChanges() {
        String reqHash = "1".repeat(64);
        String resHash = "2".repeat(64);
        String prevHash = "3".repeat(64);
        String partnerId = "partner-X";

        ChainHashEntry entry1 = new ChainHashEntry(partnerId, reqHash, resHash,
                Instant.parse("2026-06-22T10:00:00Z"), prevHash);
        ChainHashEntry entry2 = new ChainHashEntry(partnerId, reqHash, resHash,
                Instant.parse("2026-06-22T10:00:01Z"), prevHash);

        assertThat(calculator.calculate(entry1)).isNotEqualTo(calculator.calculate(entry2));
    }

    @Test
    void shouldThrowWhenPartnerIdIsNull() {
        Instant ts = Instant.now();
        ChainHashEntry entry = new ChainHashEntry(null, "a".repeat(64), "b".repeat(64), ts, "c".repeat(64));
        assertThatThrownBy(() -> calculator.calculate(entry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowWhenEntryIsNull() {
        assertThatThrownBy(() -> calculator.calculate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldChainCorrectly() {
        // Simular dos registros consecutivos del mismo partner
        Instant ts1 = Instant.parse("2026-06-22T10:00:00Z");
        Instant ts2 = Instant.parse("2026-06-22T10:01:00Z");
        String partnerId = "partner-chain-test";

        ChainHashEntry entry1 = new ChainHashEntry(
                partnerId, "a".repeat(64), "b".repeat(64), ts1, ChainHashCalculator.GENESIS_PREV_HASH);
        String chain1 = calculator.calculate(entry1);

        // El segundo registro usa el chain_hash del primero como prev_hash
        ChainHashEntry entry2 = new ChainHashEntry(
                partnerId, "c".repeat(64), "d".repeat(64), ts2, chain1);
        String chain2 = calculator.calculate(entry2);

        // chain2 debe ser diferente a chain1
        assertThat(chain2).isNotEqualTo(chain1);
        assertThat(chain2).hasSize(64).matches("[0-9a-f]{64}");
    }
}
