package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.connection.BridgeAnalyzerConnectionRuntime;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileReceiptContext;
import org.itech.ahb.file.FileResultRenderer;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.RenderedDelivery;
import org.itech.ahb.routing.NormalizedBundleRenderer.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Replays a RADIONE RP033 export through a saved FILE connection into what OpenELIS receives.
 * {@code -Dradione.export=<zip>} replays another export than the reconstruction, such as the captured one.
 */
class RadioneRp033BaselineProfileTest {

  private static final Path EXPORT = Path.of(
    System.getProperty("radione.export", "src/test/resources/radione-examples/" + RadioneExports.NEGATIVE_676 + ".zip")
  );

  @TempDir
  Path imports;

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void theProfileIsValidAndFingerprinted() throws Exception {
    ObjectNode profile = profile();
    assertThat(new AnalyzerProfileValidator(json).validationIssues(profile)).isEmpty();
    assertThat(profile.path("schemaVersion").asText()).isEqualTo("2.0");
    assertThat(profile.path("catalog").path("revision").asInt()).isEqualTo(1);
    assertThat(profile.path("protocol").path("name").asText()).isEqualTo("FILE");
  }

  @Test
  void theTracedNegativeRunIsEachWellsTargetAndInternalControlCallForSample676() throws Exception {
    List<RenderedDelivery> deliveries = delivered(render(EXPORT.getFileName().toString(), Files.readAllBytes(EXPORT)));

    assertThat(deliveries).extracting(RenderedDelivery::accession).containsExactly("676");
    // Wells C and D, and the unused channels of A and B, carry no call and send nothing.
    assertThat(records(deliveries.get(0))).containsExactly(
      "Ebola 86518-8 | main | N LA11883-8 | PATIENT",
      "Ebola 86518-8 | IC | P LA11882-0 | PATIENT",
      "Marburg 86574-1 | main | N LA11883-8 | PATIENT",
      "Marburg 86574-1 | IC | P LA11882-0 | PATIENT"
    );
  }

  @Test
  void anEbolaDetectedRunSendsTheDetectedCall() throws Exception {
    String folder = RadioneExports.NEGATIVE_676.replace("_676_", "_677_");
    List<RenderedDelivery> deliveries = delivered(
      render(folder + ".zip", RadioneExports.export(folder, "677", RadioneExports.SERIAL, "P", "P", "N", "P"))
    );

    assertThat(records(deliveries.get(0))).first().isEqualTo("Ebola 86518-8 | main | P LA11882-0 | PATIENT");
  }

  @Test
  void aMarburgWellWhoseInternalControlFailedSendsBothNegativeCallsForOpenElisToCallInvalid() throws Exception {
    String folder = RadioneExports.NEGATIVE_676.replace("_676_", "_678_");
    List<RenderedDelivery> deliveries = delivered(
      render(folder + ".zip", RadioneExports.export(folder, "678", RadioneExports.SERIAL, "N", "P", "N", "N"))
    );

    assertThat(records(deliveries.get(0))).endsWith(
      "Marburg 86574-1 | main | N LA11883-8 | PATIENT",
      "Marburg 86574-1 | IC | N LA11883-8 | PATIENT"
    );
  }

  @ParameterizedTest(name = "{3}")
  @CsvSource(
    delimiter = '|',
    value = {
      "_676_RP033_ | 677 | KM010-BE0027 | names sample 676 but its run record names sample 677",
      "_676_RP033_ | 676 | KM010-BE0031 | names instrument 0027 but its results come from instrument KM010-BE0031",
      "_676_RP038_ | 676 | KM010-BE0027 | is not named as the profile expects"
    }
  )
  void anExportWhosePartsDisagreeIsHeldForReview(String sampleAndKit, String sampleNo, String serial, String why)
    throws Exception {
    String folder = RadioneExports.NEGATIVE_676.replace("_676_RP033_", sampleAndKit);
    Outcome outcome = render(folder + ".zip", RadioneExports.export(folder, sampleNo, serial, "N", "P", "N", "P"));

    assertThat(outcome).isInstanceOf(Outcome.Failed.class);
    Outcome.Failed held = (Outcome.Failed) outcome;
    assertThat(held.reason()).isEqualTo(FailureReason.RENDER_ERROR);
    assertThat(held.message()).isEqualTo("Held for review: Export " + folder + " " + why);
  }

  @ParameterizedTest
  @ValueSource(strings = { "PC-0001", "NTC-0001" })
  void aControlRunNeverPostsToAPatient(String sampleNo) throws Exception {
    String folder = RadioneExports.NEGATIVE_676.replace("_676_", "_" + sampleNo + "_");
    List<RenderedDelivery> deliveries = delivered(
      render(folder + ".zip", RadioneExports.export(folder, sampleNo, RadioneExports.SERIAL, "N", "P", "N", "P"))
    );

    assertThat(deliveries).extracting(RenderedDelivery::accession).containsExactly(sampleNo);
    assertThat(records(deliveries.get(0))).hasSize(4).allSatisfy(record -> assertThat(record).endsWith("| CONTROL"));
  }

  private Outcome render(String fileName, byte[] export) throws Exception {
    ObjectNode profile = profile();
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    FileConfig files = new FileConfig();
    files.setImportRoots(List.of(imports.toString()));
    new BridgeAnalyzerConnectionRuntime(registry, new FileWatcher(files, null, null), null, null).activate(
      connection(profile),
      profile
    );
    AnalyzerEntry entry = registry.getRegisteredAnalyzers().values().iterator().next();
    FileReceiptContext context = FileReceiptContext.capture(imports.resolve(fileName), entry, null);
    return FileResultRenderer.render(export, json.writeValueAsString(context), "http://openelis.test/analyzer/fhir");
  }

  private static List<RenderedDelivery> delivered(Outcome outcome) {
    assertThat(outcome).isInstanceOf(Outcome.Rendered.class);
    return ((Outcome.Rendered) outcome).deliveries();
  }

  /** Each Observation as test code and LOINC | sub-identity | canonical answer and its code | classification. */
  private List<String> records(RenderedDelivery delivery) throws Exception {
    List<String> records = new ArrayList<>();
    for (JsonNode entry : json.readTree(delivery.fhirJson()).path("entry")) {
      JsonNode observation = entry.path("resource");
      if (!"Observation".equals(observation.path("resourceType").asText())) continue;
      List<String> test = new ArrayList<>();
      observation.path("code").path("coding").forEach(coding -> test.add(coding.path("code").asText()));
      String subIdentity = "main";
      String answer = "";
      String classification = "";
      for (JsonNode extension : observation.path("extension")) {
        String url = extension.path("url").asText();
        if (url.endsWith("/observation-v2-subid")) subIdentity = extension.findPath("valueString").asText();
        if (url.endsWith("/analyzer-canonical-answer")) answer = extension.path("valueString").asText();
        if (url.endsWith("/analyzer-result-classification")) classification = extension.path("valueCode").asText();
      }
      String answerCode = observation.path("valueCodeableConcept").path("coding").path(0).path("code").asText();
      records.add(
        String.join(" ", test) + " | " + subIdentity + " | " + answer + " " + answerCode + " | " + classification
      );
    }
    return records;
  }

  private ObjectNode connection(ObjectNode profile) {
    ObjectNode connection = json.createObjectNode();
    connection.put("connectionId", "00000000-0000-0000-0000-000000001610");
    connection.put("clientAnalyzerId", "oe-radione");
    connection.put("displayName", "RADIONE bench");
    ObjectNode profileRef = connection.putObject("profileRef");
    profileRef.put("profileId", profile.path("profileMeta").path("id").asText());
    profileRef.put("revision", profile.path("catalog").path("revision").asInt());
    profileRef.put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    connection.put("configRevision", 1);
    connection.put("configFingerprint", "sha256:" + "1".repeat(64));
    ObjectNode values = connection.putObject("values");
    values.setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    values.put("directory", imports.toString());
    return connection;
  }

  private ObjectNode profile() {
    return PackagedProfiles.load(json, "kh-medical-radione-rp033");
  }
}
