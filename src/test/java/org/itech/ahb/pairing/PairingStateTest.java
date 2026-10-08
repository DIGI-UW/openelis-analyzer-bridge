package org.itech.ahb.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.itech.ahb.pairing.PairingState.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PairingStateTest {

  private static final String OPENELIS = "a".repeat(64);
  private static final String OTHER = "b".repeat(64);

  @TempDir
  Path directory;

  @Test
  void aConfiguredCodePairsOnceAndThePairingSurvivesARestart() {
    PairingState state = new PairingState(directory, "dev-pairing-code");
    assertThat(state.isPaired()).isFalse();
    assertThat(state.isOpen()).isTrue();

    assertThat(state.pair("wrong-code", OPENELIS, OPENELIS)).isEqualTo(Outcome.WRONG_CODE);
    assertThat(state.pair("dev-pairing-code", OPENELIS, OPENELIS)).isEqualTo(Outcome.PAIRED);

    assertThat(state.isPaired()).isTrue();
    assertThat(state.isOpen()).isFalse();
    assertThat(state.isPeer(OPENELIS)).isTrue();
    assertThat(state.isPeer(OTHER)).isFalse();
    assertThat(state.pair("dev-pairing-code", OTHER, OTHER)).isEqualTo(Outcome.CLOSED);

    PairingState restarted = new PairingState(directory, "dev-pairing-code");
    assertThat(restarted.isPaired()).isTrue();
    assertThat(restarted.isOpen()).isFalse();
    assertThat(restarted.isPeer(OPENELIS)).isTrue();
    assertThat(restarted.trustsServer(OPENELIS)).isTrue();
  }

  @Test
  void withoutAConfiguredCodeAnUnpairedBridgeGeneratesOne() {
    PairingState state = new PairingState(directory, "");

    String code = state.generatedCode().orElseThrow();
    assertThat(code).hasSizeGreaterThanOrEqualTo(16);
    assertThat(state.pair(code, OPENELIS, OTHER)).isEqualTo(Outcome.PAIRED);
    assertThat(state.trustsServer(OTHER)).isTrue();
    assertThat(state.trustsServer(OPENELIS)).isFalse();
    assertThat(new PairingState(directory, "").generatedCode()).isEmpty();
  }

  @Test
  void reEnteringPairingTakesANewCode() {
    new PairingState(directory, "first-code-1234").pair("first-code-1234", OPENELIS, OPENELIS);

    PairingState sameCode = new PairingState(directory, "first-code-1234");
    assertThat(sameCode.isOpen()).isFalse();

    PairingState newCode = new PairingState(directory, "second-code-5678");
    assertThat(newCode.isOpen()).isTrue();
    assertThat(newCode.isPeer(OPENELIS)).isTrue();
    assertThat(newCode.pair("second-code-5678", OTHER, OTHER)).isEqualTo(Outcome.PAIRED);
    assertThat(newCode.isPeer(OTHER)).isTrue();
    assertThat(newCode.isPeer(OPENELIS)).isFalse();
  }

  @Test
  void repeatedWrongCodesClosePairingUntilRestart() {
    PairingState state = new PairingState(directory, "the-right-code-1");
    for (int i = 0; i < PairingState.MAX_FAILED_ATTEMPTS; i++) {
      assertThat(state.pair("guess-" + i, OPENELIS, OPENELIS)).isEqualTo(Outcome.WRONG_CODE);
    }

    assertThat(state.pair("the-right-code-1", OPENELIS, OPENELIS)).isEqualTo(Outcome.CLOSED);
    assertThat(state.isPaired()).isFalse();
    assertThat(new PairingState(directory, "the-right-code-1").isOpen()).isTrue();
  }
}
