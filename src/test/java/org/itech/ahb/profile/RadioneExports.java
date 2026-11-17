package org.itech.ahb.profile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** RADIONE RP033 run exports laid out as the export captured at the DRC lab for OGC-1610. */
final class RadioneExports {

  static final String NEGATIVE_676 = "RadioOne data Export_676_RP033_2026-10-10-00-22-19_2_0027";
  static final String SERIAL = "KM010-BE0027";

  private static final List<String> WELLS = List.of("A", "B", "C", "D");
  private static final Map<String, List<Integer>> LISTED_CHANNELS = Map.of(
    "A",
    List.of(1, 4),
    "B",
    List.of(1, 4),
    "C",
    List.of(1, 2, 4),
    "D",
    List.of(1, 2, 3, 4)
  );
  private static final List<String> HEADER = List.of("Location", "Target", "CT", "Copies", "P/N");
  private static final Map<String, Double> CT_WHEN_DETECTED = Map.of(
    "WellA_CH1",
    23.18,
    "WellA_CH4",
    25.61,
    "WellB_CH1",
    24.02,
    "WellB_CH4",
    25.25
  );

  private RadioneExports() {}

  /** An export whose four used channels carry these calls, in the order Ebola, its IC, Marburg, its IC. */
  static byte[] export(String folder, String sampleNo, String serial, String... calls) throws IOException {
    String[] tokens = folder.split("_");
    return exportRunAt(folder, tokens[tokens.length - 3] + "_" + tokens[tokens.length - 2], sampleNo, serial, calls);
  }

  /** As {@link #export}, with the run time its result sheet records. */
  static byte[] exportRunAt(String folder, String runTime, String sampleNo, String serial, String... calls)
    throws IOException {
    Map<String, String> called = Map.of(
      "WellA_CH1",
      calls[0],
      "WellA_CH4",
      calls[1],
      "WellB_CH1",
      calls[2],
      "WellB_CH4",
      calls[3]
    );
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(folder + "/"));
      zip.closeEntry();
      put(zip, folder + "/data.xlsx", workbook(runTime, serial, called));
      put(zip, folder + "/PC", ("{\"sampleno\":\"" + sampleNo + "\"}").getBytes(StandardCharsets.UTF_8));
      put(zip, folder + "/WellA_CH1.png", new byte[] { 0 });
    }
    return bytes.toByteArray();
  }

  private static byte[] workbook(String runTime, String serial, Map<String, String> called) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet result = workbook.createSheet("Result");
      Row instrument = result.createRow(0);
      List<String> versions = List.of(
        "Serial Number",
        serial,
        "Software Version",
        "NATV1.0.17.6",
        "System Version",
        "ITAB-01S.V203.20241023"
      );
      for (int column = 0; column < versions.size(); column++) {
        instrument.createCell(column).setCellValue(versions.get(column));
      }
      Row runTimeRow = result.createRow(1);
      runTimeRow.createCell(0).setCellValue("Run Time");
      runTimeRow.createCell(1).setCellValue(runTime);
      Row header = result.createRow(2);
      for (int column = 0; column < HEADER.size(); column++) {
        header.createCell(column).setCellValue(HEADER.get(column));
      }
      int rowIndex = 3;
      for (String well : WELLS) {
        for (int channel : LISTED_CHANNELS.get(well)) {
          String location = "Well" + well + "_CH" + channel;
          String call = called.get(location);
          Row row = result.createRow(rowIndex++);
          row.createCell(0).setCellValue(location);
          row.createCell(1).setCellValue(target(well, channel));
          if ("P".equals(call)) {
            row.createCell(2).setCellValue(CT_WHEN_DETECTED.get(location));
          } else {
            row.createCell(2).setCellValue("NA");
          }
          row.createCell(3).setCellValue("NA");
          row.createCell(4).setCellValue(call == null ? "" : call);
        }
      }
      for (String other : List.of("Project", "Raw", "Origin", "Filter", "DeltaRn&Normalize", "Image")) {
        workbook.createSheet(other);
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      workbook.write(bytes);
      return bytes.toByteArray();
    }
  }

  private static String target(String well, int channel) {
    if (channel == 1 && "A".equals(well)) return "Ebola";
    if (channel == 1 && "B".equals(well)) return "Marburg";
    if (channel == 4 && ("A".equals(well) || "B".equals(well))) return "IC";
    return List.of("FAM", "VIC", "ROX", "CY5").get(channel - 1);
  }

  private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }
}
