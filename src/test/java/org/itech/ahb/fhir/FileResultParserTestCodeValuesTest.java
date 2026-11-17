package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Madagascar FluoroCycler XT workbook writes one row per detection channel under one sample
 * ID: channel 1 carries the target's concentration and call, channel 2 the internal control's
 * validity. The profile says what the channel numbers mean, so the rows become the records they
 * are and nothing else.
 */
@DisplayName("A file's test-code column is read through the profile's test_code_values")
class FileResultParserTestCodeValuesTest {

  private static final String[] HEADERS = { "Row", "Col", "Sample ID", "Type", "Calc. Conc.", "Result" };
  private static final Map<String, String> COLUMNS = Map.of(
    "Sample ID",
    "sampleId",
    "Type",
    "qcTask",
    "Col",
    "testCode",
    "Calc. Conc.",
    "result",
    "Result",
    "interpretation"
  );
  private static final Map<String, String> CHANNELS = Map.of("1", "VIH-1", "2", "IC");

  @Test
  void channelOneIsTheTargetAndChannelTwoTheInternalControl() {
    List<HL7ResultParser.ParsedResults> parsed = parse(
      new Object[][] {
        { "A", 1, "S1", "Unknown", 1250, "Positive" },
        { "A", 2, "S1", "Unknown", null, "Valid" },
        { "B", 1, "S2", "Unknown", null, "Negative" },
        { "B", 2, "S2", "Unknown", null, "Invalid" }
      },
      CHANNELS
    );

    assertThat(parsed).hasSize(2);
    HL7ResultParser.ParsedResults first = byAccession(parsed, "S1");
    assertThat(first.results()).extracting(FhirBundleBuilder.AnalyzerResult::testCode).containsExactly("VIH-1", "IC");
    assertThat(first.results().get(0).value()).isEqualTo("1250");
    assertThat(first.results().get(1).value()).isEqualTo("Valid");
    HL7ResultParser.ParsedResults second = byAccession(parsed, "S2");
    assertThat(second.results()).extracting(FhirBundleBuilder.AnalyzerResult::testCode).containsExactly("VIH-1", "IC");
    assertThat(second.results().get(0).value()).isEqualTo("Negative");
    assertThat(second.results().get(1).value()).isEqualTo("Invalid");
  }

  @Test
  void aChannelTheProfileDoesNotNameKeepsItsNumberForOpenElisToHold() {
    List<HL7ResultParser.ParsedResults> parsed = parse(
      new Object[][] { { "A", 3, "S1", "Unknown", null, "Positive" } },
      CHANNELS
    );

    assertThat(parsed.get(0).results()).extracting(FhirBundleBuilder.AnalyzerResult::testCode).containsExactly("3");
  }

  @Test
  void aValueTheProfileNamesAsAComponentIsThatComponentsRecordOfItsTest() {
    List<FhirBundleBuilder.AnalyzerResult> results = FileResultParser.parse(
      workbook(
        new Object[][] { { "A", 1, "S1", "Unknown", null, "Positive" }, { "A", 2, "S1", "Unknown", null, "Valid" } }
      ),
      COLUMNS,
      null,
      ControlResultRecognition.none(),
      null,
      new TabularResultValueSelection(List.of("result", "interpretation")),
      Map.of("1", "VIH-1", "2", "VIH-1"),
      Map.of("2", "IC")
    )
      .get(0)
      .withTabularReading(null)
      .results();

    assertThat(results)
      .extracting(result -> result.testCode() + " " + result.parts().subIdentity() + " " + result.parts().call())
      .containsExactly("VIH-1  Positive", "VIH-1 IC Valid");
  }

  @Test
  void withoutTheTranslationTheColumnIsReadAsItIs() {
    List<HL7ResultParser.ParsedResults> parsed = parse(
      new Object[][] { { "A", 1, "S1", "Unknown", 1250, "Positive" } },
      Map.of()
    );

    assertThat(parsed.get(0).results()).extracting(FhirBundleBuilder.AnalyzerResult::testCode).containsExactly("1");
  }

  private static HL7ResultParser.ParsedResults byAccession(List<HL7ResultParser.ParsedResults> parsed, String id) {
    return parsed.stream().filter(results -> id.equals(results.accessionNumber())).findFirst().orElseThrow();
  }

  private static List<HL7ResultParser.ParsedResults> parse(Object[][] rows, Map<String, String> testCodeValues) {
    return FileResultParser.parse(
      workbook(rows),
      COLUMNS,
      null,
      ControlResultRecognition.none(),
      null,
      new TabularResultValueSelection(List.of("result", "interpretation")),
      testCodeValues
    );
  }

  private static InputStream workbook(Object[][] rows) {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Results");
      Row header = sheet.createRow(0);
      for (int column = 0; column < HEADERS.length; column++) {
        header.createCell(column).setCellValue(HEADERS[column]);
      }
      for (int index = 0; index < rows.length; index++) {
        Row row = sheet.createRow(index + 1);
        for (int column = 0; column < rows[index].length; column++) {
          Object value = rows[index][column];
          if (value instanceof String text) {
            row.createCell(column).setCellValue(text);
          } else if (value instanceof Number number) {
            row.createCell(column).setCellValue(number.doubleValue());
          }
        }
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      workbook.write(bytes);
      return new ByteArrayInputStream(bytes.toByteArray());
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
