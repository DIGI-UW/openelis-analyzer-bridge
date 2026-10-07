package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Messages an analyzer port can receive from anyone on the network. Each must parse in time
 * proportional to its size and either produce results or none, never an exception.
 */
class ParserInputBoundsTest {

  private static final AstmResultRecordSelection ALL_RESULTS = AstmResultRecordSelection.all();

  private static ParsedResults astm(String orderSpecimen, String resultValue) {
    return ASTMResultParser.parseRaw(
      "H|\\^&|||Analyzer\rP|1\rO|1|" + orderSpecimen + "\rR|1|^^^WBC|" + resultValue + "|10*3/uL\rL|1\r",
      ControlResultRecognition.none(),
      ALL_RESULTS
    );
  }

  private static ParsedResults hl7(String valueType, String value) {
    return HL7ResultParser.parseRaw(
      "MSH|^~\\&|APP|LAB|OE|LAB|20260326||ORU^R01|M1|P|2.5.1\rPID|1||PAT\rOBR|1|P1|ACC-1|CBC\r" +
      "OBX|1|" + valueType + "|WBC||" + value + "|10*3/uL\r",
      ControlResultRecognition.none()
    );
  }

  @Test
  @DisplayName("an ASTM order whose specimen id is only separators has nothing to deliver instead of failing")
  void aSeparatorOnlySpecimenIdHasNothingToDeliver() {
    assertNull(astm("^^", "7.5"), "without an accession there is no deliverable result");
    assertEquals("ACC-1", astm("ACC-1^LOC", "7.5").accessionNumber());
  }

  @Test
  @DisplayName("a long run of ASTM component separators is cleaned in linear time")
  void aLongSeparatorRunIsCleanedInLinearTime() {
    String value = "^".repeat(200_000) + "x";

    ParsedResults parsed = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> astm("ACC-1", value));

    assertEquals("^".repeat(199_999) + "x", parsed.results().get(0).value());
  }

  @Test
  @DisplayName("an ASTM value is numeric only when it is a plain decimal of bounded size")
  void astmNumericValuesAreBounded() {
    assertTrue(astm("ACC-1", "7.5").results().get(0).isNumeric());
    assertFalse(astm("ACC-1", "1e2147483627").results().get(0).isNumeric());
    AnalyzerResult belowRange = astm("ACC-1", "<5").results().get(0);
    assertFalse(belowRange.isNumeric(), "a comparison is a qualitative assertion, not a number");
    assertEquals("<5", belowRange.value());
  }

  @Test
  @DisplayName("an HL7 numeric observation is numeric only when its value is a plain decimal of bounded size")
  void hl7NumericValuesAreBounded() {
    assertTrue(hl7("NM", "7.5").results().get(0).isNumeric());
    assertFalse(hl7("NM", "1e2147483627").results().get(0).isNumeric());
    assertFalse(hl7("SN", "<^5").results().get(0).isNumeric());
  }

  @Test
  @DisplayName("an HL7 message with one huge segment and many small ones parses in linear time")
  void manySegmentsAfterAHugeOneParseInLinearTime() {
    StringBuilder message = new StringBuilder("MSH|^~\\&|APP|LAB|OE|LAB|20260326||ORU^R01|M1|P|2.5.1\r");
    message.append("ZZZ").append("|a".repeat(150_000)).append('\r');
    message.append("NTE|1|x\r".repeat(4_000));
    message.append("PID|1||PAT\rOBR|1|P1|ACC-1|CBC\rOBX|1|NM|WBC||7.5|10*3/uL\r");

    ParsedResults parsed = assertTimeoutPreemptively(
      Duration.ofSeconds(3),
      () -> HL7ResultParser.parseRaw(message.toString(), ControlResultRecognition.none())
    );

    assertEquals("ACC-1", parsed.accessionNumber());
    assertEquals(1, parsed.results().size());
  }
}
