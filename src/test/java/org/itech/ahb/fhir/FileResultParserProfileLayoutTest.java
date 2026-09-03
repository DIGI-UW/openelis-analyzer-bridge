package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.Test;

class FileResultParserProfileLayoutTest {

  @Test
  void findsAProfileConfiguredHeaderOnALaterSheet() throws Exception {
    byte[] workbookBytes;
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      workbook.createSheet("Summary").createRow(0).createCell(0).setCellValue("Not result data");
      var resultSheet = workbook.createSheet("Instrument export");
      resultSheet.createRow(0).createCell(0).setCellValue("Run metadata");
      var header = resultSheet.createRow(3);
      header.createCell(0).setCellValue("Specimen");
      header.createCell(1).setCellValue("Assay");
      header.createCell(2).setCellValue("Measured value");
      var result = resultSheet.createRow(4);
      result.createCell(0).setCellValue("SAMPLE-001");
      result.createCell(1).setCellValue("ASSAY-A");
      result.createCell(2).setCellValue("42.5");
      workbook.write(output);
      workbookBytes = output.toByteArray();
    }

    TabularFileLayout layout = TabularFileLayout.headerScan(
      List.of("Results"),
      "Specimen",
      5,
      100
    );

    var parsed = FileResultParser.parse(
      new ByteArrayInputStream(workbookBytes),
      Map.of("Specimen", "sampleId", "Assay", "testCode", "Measured value", "result"),
      null,
      ControlResultRecognition.none(),
      layout
    );

    assertThat(parsed).hasSize(1);
    assertThat(parsed.get(0).accessionNumber()).isEqualTo("SAMPLE-001");
    assertThat(parsed.get(0).results()).singleElement().satisfies(result -> {
      assertThat(result.testCode()).isEqualTo("ASSAY-A");
      assertThat(result.value()).isEqualTo("42.5");
    });
  }
}
