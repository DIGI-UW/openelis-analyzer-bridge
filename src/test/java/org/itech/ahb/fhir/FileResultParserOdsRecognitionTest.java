package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.Test;

class FileResultParserOdsRecognitionTest {

  @Test
  void odsUsesRulesFromThePinnedProfile() throws Exception {
    ControlResultRecognition recognition = TestControlRecognitions.rule(
      "positive-control",
      "FIELD_EQUALS",
      "QC_TASK",
      "Positive",
      null,
      "POSITIVE"
    );

    var parsed = FileResultParser.parseOds(
      new ByteArrayInputStream(odsFixture()),
      mappings(),
      null,
      recognition
    );

    assertThat(parsed).singleElement().satisfies(results -> {
      assertThat(results.accessionNumber()).isEqualTo("POS-CONTROL");
      assertThat(results.results()).singleElement().satisfies(result -> {
        assertThat(result.isControl()).isTrue();
        assertThat(result.controlType()).isEqualTo("POSITIVE");
      });
    });
  }

  @Test
  void odsNoneDoesNotUseTheFormerTaskHeuristic() throws Exception {
    var parsed = FileResultParser.parseOds(
      new ByteArrayInputStream(odsFixture()),
      mappings(),
      null,
      ControlResultRecognition.none()
    );

    assertThat(parsed)
      .singleElement()
      .satisfies(results ->
        assertThat(results.results()).singleElement().satisfies(result ->
          assertThat(result.isControl()).isFalse()
        )
      );
  }

  @Test
  void odsHeaderIdentityComesFromTheProfileColumnMapping() throws Exception {
    Map<String, String> profileMappings = Map.of(
      "Specimen",
      "sampleId",
      "Target",
      "testCode",
      "Result",
      "result",
      "Type",
      "qcTask"
    );

    var parsed = FileResultParser.parseOds(
      new ByteArrayInputStream(odsFixture("Specimen")),
      profileMappings,
      null,
      ControlResultRecognition.none()
    );

    assertThat(parsed).singleElement().satisfies(results ->
      assertThat(results.accessionNumber()).isEqualTo("POS-CONTROL")
    );
  }

  private static Map<String, String> mappings() {
    return Map.of(
      "Sample ID",
      "sampleId",
      "Target",
      "testCode",
      "Result",
      "result",
      "Type",
      "qcTask"
    );
  }

  private static byte[] odsFixture() throws Exception {
    return odsFixture("Sample ID");
  }

  private static byte[] odsFixture(String sampleHeader) throws Exception {
    String content = """
      <?xml version="1.0" encoding="UTF-8"?>
      <office:document-content
          xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
          xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
          xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
        <office:body>
          <office:spreadsheet>
            <table:table table:name="Results">
              <table:table-row>
                <table:table-cell><text:p>%s</text:p></table:table-cell>
                <table:table-cell><text:p>Target</text:p></table:table-cell>
                <table:table-cell><text:p>Result</text:p></table:table-cell>
                <table:table-cell><text:p>Type</text:p></table:table-cell>
              </table:table-row>
              <table:table-row>
                <table:table-cell><text:p>POS-CONTROL</text:p></table:table-cell>
                <table:table-cell><text:p>VIH-1</text:p></table:table-cell>
                <table:table-cell><text:p>Positive</text:p></table:table-cell>
                <table:table-cell><text:p>Positive</text:p></table:table-cell>
              </table:table-row>
            </table:table>
          </office:spreadsheet>
        </office:body>
      </office:document-content>
      """.formatted(sampleHeader);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("content.xml"));
      zip.write(content.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return output.toByteArray();
  }
}
