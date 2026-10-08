package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Specimen;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerContext;
import org.itech.ahb.fhir.FhirBundleBuilder.DeviceInfo;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.BaselineProfileFixtures;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ResultReading;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Cepheid's own examples for the Xpert HIV-1 Viral Load XC assay (303-0251 Rev A 2.1.1), read by the
 * profile and put in a bundle: every part the instrument reported is in its FHIR slot, in the
 * shape OpenELIS reads (rules 9, 10, 11 and 17).
 */
class ASTMResultPartsBundleTest {

  private static final FhirContext FHIR = FhirContext.forR4();
  private static final String ROOT = "https://openelis-global.org/fhir/StructureDefinition/";
  private static final String SUB_ID = "http://hl7.org/fhir/StructureDefinition/observation-v2-subid";
  private static final String INTERPRETATION = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
  private static final String RAW_CODE = "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code";
  private static final String INSTRUMENT = "MSEDGEWIN10^810085^702922^992008573^12902^20230618";
  private static final String ASSAY = "^^^HIVVL^Xpert HIV-1 Viral Load XC^3^^";

  private static ResultReading reading;

  @BeforeAll
  static void readTheProfile() throws Exception {
    reading = ResultReading.fromProfile(BaselineProfileFixtures.genexpertHivViralLoad(new ObjectMapper()));
  }

  @Test
  void aCallBelowTheRangeIsTheLimitWithItsComparatorAndTheCallAsItsInterpretation() {
    Bundle bundle = bundle(
      header() +
      order("HIV-1 30cp") +
      main("DETECTED^", "40.00 to 10000000.00", "<") +
      analyte("HIV-1", "POS^") +
      complement("HIV-1", "Ct", "^38.4") +
      "L|1|N\r"
    );

    Observation main = observation(bundle, "HIVVL", "");
    Quantity quantity = main.getValueQuantity();
    assertThat(quantity.getValue()).isEqualByComparingTo(new BigDecimal("40.00"));
    assertThat(quantity.getComparator()).isEqualTo(Quantity.QuantityComparator.LESS_THAN);
    assertThat(quantity.getUnit()).isEqualTo("copies/mL");
    assertThat(main.getInterpretation()).hasSize(1);
    assertThat(main.getInterpretationFirstRep().getText()).isEqualTo("DETECTED");
    assertThat(main.getInterpretationFirstRep().getCodingFirstRep().getSystem()).isEqualTo(INTERPRETATION);
    assertThat(main.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("DET");
    assertThat(extension(main, "analyzer-raw-value")).isEqualTo("DETECTED");
    assertThat(main.getMethod().getText()).isEqualTo("Xpert HIV-1 Viral Load XC");
    assertThat(
      main.getMethod().getExtensionByUrl(ROOT + "analyzer-assay-version").getValue().primitiveValue()
    ).isEqualTo("3");
    assertThat(extension(main, "analyzer-instrument-identification")).isEqualTo(INSTRUMENT);
    assertThat(main.getExtensionByUrl(SUB_ID)).isNull();
    assertThat(main.getPerformer()).isEmpty();

    Observation analyte = observation(bundle, "HIVVL", "HIV-1");
    assertThat(analyte.getValueStringType().getValue()).isEqualTo("POS");
    assertThat(analyte.getInterpretation()).isEmpty();
    Observation ct = observation(bundle, "HIVVL", "HIV-1&Ct");
    assertThat(ct.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("38.4"));
    assertThat(ct.getValueQuantity().hasComparator()).isFalse();
  }

  @Test
  void aQuantifiedResultIsItsNumberAndItsLogIsItsOwnObservation() {
    Bundle bundle = bundle(
      header() +
      order("HIV-1 1E3cp") +
      main("^1009.64", "40.00 to 10000000.00", "N") +
      log("^3.00", "1.60 to 7.00", "N") +
      "L|1|N\r"
    );

    Observation main = observation(bundle, "HIVVL", "");
    assertThat(main.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("1009.64"));
    assertThat(main.getValueQuantity().hasComparator()).isFalse();
    assertThat(extension(main, "analyzer-raw-value")).isEqualTo("1009.64");
    // The instrument's flag is an interpretation that states no call, so OpenELIS shows it as sent.
    assertThat(main.getInterpretationFirstRep().getText()).isEqualTo("N");
    assertThat(main.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("N");
    Observation log = observation(bundle, "HIVVL", "&LOG");
    assertThat(log.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("3.00"));
    assertThat(
      log.getExtensionByUrl(SUB_ID).getExtensionByUrl("original-sub-identifier").getValue().primitiveValue()
    ).isEqualTo("&LOG");
  }

  @Test
  void aCallAboveTheRangeIsTheUpperLimitWithItsComparator() {
    Bundle bundle = bundle(header() + order("HIV-1 1E7") + main("DETECTED^", "40.00 to 10000000.00", ">") + "L|1|N\r");

    Quantity quantity = observation(bundle, "HIVVL", "").getValueQuantity();
    assertThat(quantity.getValue()).isEqualByComparingTo(new BigDecimal("10000000.00"));
    assertThat(quantity.getComparator()).isEqualTo(Quantity.QuantityComparator.GREATER_THAN);
  }

  @Test
  void aNotDetectedCallIsTheValueAndNeverZero() {
    Bundle bundle = bundle(
      header() + order("HIV-1 Negative") + main("NOT DETECTED^", "40.00 to 10000000.00", "A") + "L|1|N\r"
    );

    Observation main = observation(bundle, "HIVVL", "");
    assertThat(main.hasValueQuantity()).isFalse();
    assertThat(main.getValueStringType().getValue()).isEqualTo("NOT DETECTED");
    assertThat(main.getInterpretationFirstRep().getText()).isEqualTo("A");
  }

  @Test
  void aRunThatErroredHasNoValueAndSaysWhyAndKeepsItsText() {
    Bundle bundle = bundle(
      header() +
      order("Error") +
      main("ERROR^", "40.00 to 10000000.00", "A") +
      "C|1|I|Error^2097^Operation terminated^Error 2097: Assay-Specific Termination Error #2: 47, 8, 1, 0^20221116010549|N\r" +
      analyte("HIV-1", "NO RESULT^") +
      "L|1|N\r"
    );

    Observation main = observation(bundle, "HIVVL", "");
    assertThat(main.hasValue()).isFalse();
    assertThat(main.getDataAbsentReason().getCodingFirstRep().getCode()).isEqualTo("error");
    assertThat(extension(main, "analyzer-raw-value")).isEqualTo("ERROR");
    assertThat(main.getNote())
      .extracting(note -> note.getText())
      .containsExactly(
        "Error 2097: Operation terminated (Error 2097: Assay-Specific Termination Error #2: 47, 8, 1, 0) at 2022-11-16T01:05:49"
      );
    Observation analyte = observation(bundle, "HIVVL", "HIV-1");
    assertThat(analyte.hasValue()).isFalse();
    assertThat(analyte.getDataAbsentReason().getCodingFirstRep().getCode()).isEqualTo("error");
    assertThat(extension(analyte, "analyzer-raw-value")).isEqualTo("NO RESULT");
  }

  @Test
  void anInvalidRunIsACodedAnswerWithItsValue() {
    Bundle bundle = bundle(header() + order("Invalid") + main("INVALID^", "40.00 to 10000000.00", "A") + "L|1|N\r");

    Observation main = observation(bundle, "HIVVL", "");
    assertThat(main.getValueStringType().getValue()).isEqualTo("INVALID");
    assertThat(main.hasDataAbsentReason()).isFalse();
  }

  @Test
  void theInstrumentsPatientAndSpecimenDescriptorAreInTheBundleAsReported() {
    Bundle bundle = bundle(
      "H|@^\\|URM-1||GeneXpert PC^GeneXpert^1.0|||||GX||P|1394-97|20221202104017\r" +
      "P|1|||MRN-9|Roe^Jane^^^\r" +
      order("ACC-1") +
      main("NOT DETECTED^", "40.00 to 10000000.00", "A") +
      "L|1|N\r"
    );

    Patient patient = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Patient.class::isInstance)
      .map(Patient.class::cast)
      .findFirst()
      .orElseThrow();
    assertThat(patient.getIdentifierFirstRep().getValue()).isEqualTo("MRN-9");
    assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Roe");
    assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("Jane");
    assertThat(patient.getExtensionByUrl(ROOT + "analyzer-patient-source").getValue().primitiveValue()).isEqualTo(
      "instrument"
    );
    Observation main = observation(bundle, "HIVVL", "");
    assertThat(main.getSubject().getReference()).isNotNull();
    String patientUrl = bundle
      .getEntry()
      .stream()
      .filter(entry -> entry.getResource() instanceof Patient)
      .map(Bundle.BundleEntryComponent::getFullUrl)
      .findFirst()
      .orElseThrow();
    assertThat(main.getSubject().getReference()).isEqualTo(patientUrl);
    Specimen specimen = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Specimen.class::isInstance)
      .map(Specimen.class::cast)
      .findFirst()
      .orElseThrow();
    assertThat(specimen.getType().getText()).isEqualTo("ORH");
  }

  @Test
  void aProfileWithNoPartsBuildsTheBundleItAlwaysDid() {
    ParsedResults legacy = new ParsedResults(
      "ACC-9",
      List.of(FhirBundleBuilder.AnalyzerResult.numeric("WBC", "White Blood Cell", "7.5", "10*3/uL"))
    );

    Bundle bundle = FHIR.newJsonParser()
      .parseResource(
        Bundle.class,
        FhirBundleBuilder.buildNormalizedBundle(legacy, context(), code -> null, "astm-v1:" + "a".repeat(64))
      );

    Observation observation = observation(bundle, "WBC", "");
    assertThat(observation.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("7.5"));
    assertThat(observation.getInterpretation()).isEmpty();
    assertThat(observation.hasMethod()).isFalse();
    assertThat(observation.getExtensionByUrl(SUB_ID)).isNull();
  }

  private static Bundle bundle(String message) {
    ParsedResults parsed = ASTMResultParser.parseRaw(
      message,
      ControlResultRecognition.none(),
      AstmResultRecordSelection.all(),
      reading
    );
    assertThat(parsed).isNotNull();
    String json = FhirBundleBuilder.buildNormalizedBundle(parsed, context(), code -> null, "astm-v1:" + "a".repeat(64));
    assertConformsToTheContract(json);
    return FHIR.newJsonParser().parseResource(Bundle.class, json);
  }

  /** The bundle is the boundary OpenELIS reads: it must stay inside the published normalized-bundle schema. */
  private static void assertConformsToTheContract(String json) {
    try {
      var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
        new ObjectMapper()
          .readTree(Path.of("contracts", "analyzer", "v1", "normalized-fhir-bundle.schema.json").toFile())
      );
      assertThat(schema.validate(new ObjectMapper().readTree(json))).isEmpty();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static AnalyzerContext context() {
    return new AnalyzerContext(
      "bridge-connection-7f3c",
      "oe-analyzer-42",
      "genexpert-astm",
      8,
      "ASTM",
      "TCP",
      DeviceInfo.fromSenderToken("10.20.30.40", "GeneXpert PC^GeneXpert^1.0"),
      ControlResultRecognition.none(),
      "sha256:" + "0".repeat(64)
    );
  }

  private static Observation observation(Bundle bundle, String code, String subIdentity) {
    List<Observation> matches = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .filter(observation -> code.equals(observation.getCode().getCodingFirstRep().getCode()))
      .filter(observation -> subIdentity.equals(subIdentityOf(observation)))
      .toList();
    assertThat(matches).as(code + " " + subIdentity).hasSize(1);
    assertThat(matches.get(0).getCode().getCodingFirstRep().getSystem()).isEqualTo(RAW_CODE);
    return matches.get(0);
  }

  private static String subIdentityOf(Observation observation) {
    Extension subId = observation.getExtensionByUrl(SUB_ID);
    return subId == null ? "" : subId.getExtensionByUrl("original-sub-identifier").getValue().primitiveValue();
  }

  private static String extension(Observation observation, String name) {
    Extension extension = observation.getExtensionByUrl(ROOT + name);
    return extension == null ? null : extension.getValue().primitiveValue();
  }

  private static String header() {
    return (
      "H|@^\\|URM-9h6IUTYA-05||GeneXpert PC^GeneXpert^1.0|||||GX||P|1394-97|20221202104017\r" +
      "P|1||||^^^^|||||||||||||||||||||\r"
    );
  }

  private static String order(String specimen) {
    return "O|1|" + specimen + "||^^^HIVVL|R|20221115035816|||||||||ORH||||||||||F\r";
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
