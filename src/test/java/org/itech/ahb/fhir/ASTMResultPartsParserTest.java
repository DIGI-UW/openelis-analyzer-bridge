package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.BaselineProfileFixtures;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ResultReading;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every record Cepheid documents for the Xpert HIV-1 Viral Load XC assay (303-0251 Rev A 2.1.1,
 * 301-2002 Rev E 6.3.4.1.5 to 6.3.4.1.7) is read into the parts its profile says it carries.
 */
class ASTMResultPartsParserTest {

  private static final String INSTRUMENT = "MSEDGEWIN10^810085^702922^992008573^12902^20230618";
  private static final String ASSAY = "^^^HIVVL^Xpert HIV-1 Viral Load XC^3^^";

  private static ResultReading reading;

  @BeforeAll
  static void readTheProfile() throws Exception {
    reading = ResultReading.fromProfile(BaselineProfileFixtures.genexpertHivViralLoad(new ObjectMapper()));
  }

  @Test
  void anOffScaleCallKeepsItsComparatorAndTheLimitTheRangeGives() {
    ParsedResults parsed = parse(
      header() +
      "O|1|HIV-1 30cp||^^^HIVVL|R|20221115035816|||||||||ORH||||||||||F\r" +
      main("DETECTED^", "40.00 to 10000000.00", "<") +
      log("^", "1.60 to 7.00", "<") +
      analyte("HIV-1", "POS^") +
      complement("HIV-1", "Ct", "^38.4") +
      complement("HIV-1", "EndPt", "^257.0") +
      complement("HIV-1", "Delta Ct", "^-11.7") +
      analyte("IQS-H", "PASS^") +
      complement("IQS-H", "Ct", "^22.4") +
      complement("IQS-H", "EndPt", "^960.0") +
      complement("IQS-H", "Delta Ct", "^") +
      analyte("IQS-L", "PASS^") +
      complement("IQS-L", "Ct", "^31.9") +
      complement("IQS-L", "EndPt", "^153.0") +
      complement("IQS-L", "Delta Ct", "^") +
      "L|1|N\r"
    );

    assertThat(parsed.accessionNumber()).isEqualTo("HIV-1 30cp");
    // A record with neither a call nor a number says nothing, so it is not a result.
    assertThat(subIdentities(parsed)).containsExactly(
      "",
      "HIV-1",
      "HIV-1&Ct",
      "HIV-1&EndPt",
      "HIV-1&Delta Ct",
      "IQS-H",
      "IQS-H&Ct",
      "IQS-H&EndPt",
      "IQS-L",
      "IQS-L&Ct",
      "IQS-L&EndPt"
    );
    RecordParts main = parsed.results().get(0).parts();
    assertThat(parsed.results().get(0).testCode()).isEqualTo("HIVVL");
    assertThat(main.call()).isEqualTo("DETECTED");
    assertThat(main.number()).isNull();
    assertThat(main.comparator()).isEqualTo("<");
    assertThat(main.limit()).isEqualTo("40.00");
    assertThat(main.range()).isEqualTo("40.00 to 10000000.00");
    assertThat(parsed.results().get(0).units()).isEqualTo("copies/mL");
    assertThat(main.flags()).isEmpty();
    assertThat(main.assayName()).isEqualTo("Xpert HIV-1 Viral Load XC");
    assertThat(main.assayVersion()).isEqualTo("3");
    assertThat(main.operator()).isNull();
    assertThat(main.instrument()).isEqualTo(INSTRUMENT);
    assertThat(main.runFailed()).isFalse();
  }

  @Test
  void aQuantifiedResultCarriesItsNumberAndFlagAndItsLogIsAResultOfItsOwn() {
    ParsedResults parsed = parse(
      header() +
      "O|1|HIV-1 1E3cp||^^^HIVVL|R|20221115071014|||||||||ORH||||||||||F\r" +
      main("^1009.64", "40.00 to 10000000.00", "N") +
      log("^3.00", "1.60 to 7.00", "N") +
      analyte("HIV-1", "POS^") +
      "L|1|N\r"
    );

    RecordParts main = parsed.results().get(0).parts();
    assertThat(main.number()).isEqualTo("1009.64");
    assertThat(main.call()).isNull();
    assertThat(main.comparator()).isNull();
    assertThat(main.flags()).containsExactly("N");
    RecordParts log = parsed.results().get(1).parts();
    assertThat(parsed.results().get(1).testCode()).isEqualTo("HIVVL");
    assertThat(log.subIdentity()).isEqualTo("&LOG");
    assertThat(log.number()).isEqualTo("3.00");
    assertThat(log.assayName()).isEqualTo("Xpert HIV-1 Viral Load XC");
  }

  @Test
  void aCallAboveTheRangeKeepsTheUpperLimit() {
    ParsedResults parsed = parse(
      header() +
      "O|1|HIV-1 1E7||^^^HIVVL|R|20221115020928|||||||||ORH||||||||||F\r" +
      main("DETECTED^", "40.00 to 10000000.00", ">") +
      "L|1|N\r"
    );

    RecordParts main = parsed.results().get(0).parts();
    assertThat(main.comparator()).isEqualTo(">");
    assertThat(main.limit()).isEqualTo("10000000.00");
  }

  @Test
  void aNotDetectedCallIsTheValueAndNeverANumber() {
    ParsedResults parsed = parse(
      header() +
      "O|1|HIV-1 Negative||^^^HIVVL|R|20221115053344|||||||||ORH||||||||||F\r" +
      main("NOT DETECTED^", "40.00 to 10000000.00", "A") +
      analyte("HIV-1", "NEG^") +
      "L|1|N\r"
    );

    RecordParts main = parsed.results().get(0).parts();
    assertThat(main.call()).isEqualTo("NOT DETECTED");
    assertThat(main.number()).isNull();
    assertThat(main.limit()).isNull();
    assertThat(main.flags()).containsExactly("A");
  }

  @Test
  void aRunThatErroredIsMarkedAndItsErrorDetailTravelsWithTheRecord() {
    ParsedResults parsed = parse(
      header() +
      "O|1|Error||^^^HIVVL|R|20221116010119|||||||||ORH||||||||||F\r" +
      main("ERROR^", "40.00 to 10000000.00", "A") +
      "C|1|I|Error^2097^Operation terminated^Error 2097: Assay-Specific Termination Error #2: 47, 8, 1, 0^20221116010549|N\r" +
      log("^", "1.60 to 7.00", "A") +
      "C|1|I|Error^2097^Operation terminated^Error 2097: Assay-Specific Termination Error #2: 47, 8, 1, 0^20221116010549|N\r" +
      analyte("HIV-1", "NO RESULT^") +
      complement("HIV-1", "Ct", "^0.0") +
      analyte("IQS-H", "NO RESULT^") +
      "L|1|N\r"
    );

    List<AnalyzerResult> results = parsed.results();
    assertThat(subIdentities(parsed)).containsExactly("", "HIV-1", "HIV-1&Ct", "IQS-H");
    RecordParts main = results.get(0).parts();
    assertThat(main.call()).isEqualTo("ERROR");
    assertThat(main.runFailed()).isTrue();
    assertThat(main.notes()).containsExactly(
      "Error 2097: Operation terminated (Error 2097: Assay-Specific Termination Error #2: 47, 8, 1, 0) at 2022-11-16T01:05:49"
    );
    // An analyte the run never read is a failed run of its own; the Ct it did report is a plain number.
    assertThat(results.get(1).parts().runFailed()).isTrue();
    assertThat(results.get(2).parts().runFailed()).isFalse();
    assertThat(results.get(2).parts().number()).isEqualTo("0.0");
    assertThat(results.get(3).parts().runFailed()).isTrue();
  }

  @Test
  void aRunFailureTheInstrumentWritesInAnotherLanguageIsStillAFailure() throws Exception {
    var profile = BaselineProfileFixtures.genexpertHivViralLoad(new ObjectMapper());
    ((com.fasterxml.jackson.databind.node.ObjectNode) profile.withArray("default_test_mappings").get(0)).withObject(
        "translations"
      )
      .putArray("ERROR")
      .add("ERREUR");
    ParsedResults parsed = ASTMResultParser.parseRaw(
      header() +
      "O|1|Error||^^^HIVVL|R|20221116010119|||||||||ORH||||||||||F\r" +
      main("ERREUR^", "40.00 to 10000000.00", "A") +
      "L|1|N\r",
      ControlResultRecognition.none(),
      AstmResultRecordSelection.all(),
      ResultReading.fromProfile(profile)
    );

    assertThat(parsed.results().get(0).parts().runFailed()).isTrue();
  }

  @Test
  void anInvalidRunIsACodedAnswerNotAFailure() {
    ParsedResults parsed = parse(
      header() +
      "O|1|Invalid||^^^HIVVL|R|20221115225410|||||||||ORH||||||||||F\r" +
      main("INVALID^", "40.00 to 10000000.00", "A") +
      analyte("HIV-1", "INVALID^") +
      analyte("IQS-H", "FAIL^") +
      "L|1|N\r"
    );

    assertThat(parsed.results()).allSatisfy(result -> assertThat(result.parts().runFailed()).isFalse());
    assertThat(parsed.results().get(0).parts().call()).isEqualTo("INVALID");
  }

  @Test
  void theInstrumentsPatientAndSpecimenDescriptorAreRead() {
    ParsedResults parsed = parse(
      "H|@^\\|URM-1||GeneXpert PC^GeneXpert^1.0|||||GX||P|1394-97|20221202104017\r" +
      "P|1|||MRN-9|Roe^Jane^^^\r" +
      "O|1|ACC-1||^^^HIVVL|R|20221115035816|||||||||ORH||||||||||F\r" +
      main("NOT DETECTED^", "40.00 to 10000000.00", "A") +
      "L|1|N\r"
    );

    assertThat(parsed.patient()).isNotNull();
    assertThat(parsed.patient().identifier()).isEqualTo("MRN-9");
    assertThat(parsed.patient().family()).isEqualTo("Roe");
    assertThat(parsed.patient().given()).isEqualTo("Jane");
    assertThat(parsed.specimenDescriptor()).isEqualTo("ORH");
  }

  @Test
  void anEmptyPatientRecordReportsNoPatient() {
    ParsedResults parsed = parse(
      header() +
      "O|1|ACC-1||^^^HIVVL|R|20221115035816|||||||||ORH||||||||||F\r" +
      main("NOT DETECTED^", "40.00 to 10000000.00", "A") +
      "L|1|N\r"
    );

    assertThat(parsed.patient()).isNull();
  }

  @Test
  void aRaisedAlarmTheProfileNamesTravelsWithTheSampleAndIsNeverAResult() throws Exception {
    ObjectNode profile = BaselineProfileFixtures.genexpertHivViralLoad(new ObjectMapper());
    ObjectNode flags = profile.withObject("configDefaults").withObject("extractionOverrides").putObject("sampleFlags");
    flags.put("codeField", "R.3.4");
    flags.put("nameField", "R.3.5");
    flags.put("valueField", "R.4");
    flags.put("raisedValue", "T");
    flags.putArray("codes").add("LIPEMIA").add("HEMOLYSIS");

    ParsedResults parsed = ASTMResultParser.parseRaw(
      header() +
      "O|1|ACC-1||^^^HIVVL|R|20221115035816|||||||||ORH||||||||||F\r" +
      main("NOT DETECTED^", "40.00 to 10000000.00", "A") +
      "R|5|^^^LIPEMIA^Lipemic sample^^|T|\r" +
      "R|6|^^^HEMOLYSIS^Hemolyzed sample^^|F|\r" +
      "L|1|N\r",
      ControlResultRecognition.none(),
      AstmResultRecordSelection.all(),
      ResultReading.fromProfile(profile)
    );

    assertThat(parsed.results()).extracting(AnalyzerResult::testCode).containsOnly("HIVVL");
    assertThat(parsed.sampleFlags()).containsExactly(new SampleFlag("LIPEMIA", "Lipemic sample", "T"));
  }

  private static ParsedResults parse(String message) {
    ParsedResults parsed = ASTMResultParser.parseRaw(
      message,
      ControlResultRecognition.none(),
      AstmResultRecordSelection.all(),
      reading
    );
    assertThat(parsed).isNotNull();
    return parsed;
  }

  private static List<String> subIdentities(ParsedResults parsed) {
    return parsed.results().stream().map(result -> result.parts().subIdentity()).toList();
  }

  private static String header() {
    return (
      "H|@^\\|URM-9h6IUTYA-05||GeneXpert PC^GeneXpert^1.0|||||GX||P|1394-97|20221202104017\r" +
      "P|1||||^^^^|||||||||||||||||||||\r"
    );
  }

  private static String main(String data, String range, String flag) {
    return (
      "R|1|" +
      ASSAY +
      "|" +
      data +
      "|copies/mL|" +
      range +
      "|" +
      flag +
      "||F||<None>|20221115035816|20221115052812|" +
      INSTRUMENT +
      "\r"
    );
  }

  private static String log(String data, String range, String flag) {
    return (
      "R|2|" +
      ASSAY +
      "LOG|" +
      data +
      "|copies/mL|" +
      range +
      "|" +
      flag +
      "||F||<None>|20221115035816|20221115052812|" +
      INSTRUMENT +
      "\r"
    );
  }

  private static String analyte(String analyte, String data) {
    return "R|3|^^^HIVVL^^^" + analyte + "^|" + data + "|||\r";
  }

  private static String complement(String analyte, String complement, String data) {
    return "R|4|^^^HIVVL^^^" + analyte + "^" + complement + "|" + data + "|||\r";
  }
}
