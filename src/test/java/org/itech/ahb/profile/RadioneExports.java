package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
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
      byte[] runRecord = runRecord(folder, sampleNo, called);
      put(zip, folder + "/data.xlsx", workbook(folder, serial, called));
      put(zip, folder + "/PC", runRecord);
      String copy = "zip" + Base64.getEncoder().encodeToString(gzip(runRecord));
      put(zip, folder + "/shiyanstatedata", copy.getBytes(StandardCharsets.US_ASCII));
      byte[] curve = curve();
      for (String well : WELLS) {
        for (int channel = 1; channel <= 4; channel++) {
          put(zip, folder + "/Well" + well + "_CH" + channel + ".png", curve);
        }
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] workbook(String folder, String serial, Map<String, String> called) throws IOException {
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
      Row runTime = result.createRow(1);
      runTime.createCell(0).setCellValue("Run Time");
      runTime.createCell(1).setCellValue(runTime(folder));
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

  private static byte[] runRecord(String folder, String sampleNo, Map<String, String> called) throws IOException {
    ObjectMapper json = new ObjectMapper();
    ObjectNode record = json.createObjectNode();
    record.put("sampleno", sampleNo);
    record.put("projectname", "RP033");
    record.put("checktime", runTime(folder));
    record.put("checkuser", "");
    record.put("softversion", "1.0.17.6");
    ArrayNode kongArgs = record.putObject("modifyProject").putArray("kongArgs");
    for (int index = 0; index < 8; index++) {
      String well = index < WELLS.size() ? WELLS.get(index) : null;
      ObjectNode kong = kongArgs.addObject();
      ArrayNode names = kong.putArray("itemnames");
      ArrayNode used = kong.putArray("guangAbles");
      ArrayNode cts = kong.putArray("cts");
      ArrayNode calls = kong.putArray("item_yinyangs");
      ArrayNode cutOffs = kong.putArray("itemyuzhis");
      ArrayNode types = kong.putArray("types");
      for (int channel = 1; channel <= 4; channel++) {
        String location = "Well" + well + "_CH" + channel;
        String call = well == null ? null : called.get(location);
        names.add(well == null ? channelName(channel) : target(well, channel));
        used.add(call != null);
        cts.add("P".equals(call) ? CT_WHEN_DETECTED.get(location) : 0.0);
        calls.add("P".equals(call) ? 1 : 0);
        cutOffs.add(40);
        types.add("Unknown");
      }
    }
    return json.writeValueAsBytes(record);
  }

  private static String target(String well, int channel) {
    if (channel == 1 && "A".equals(well)) return "Ebola";
    if (channel == 1 && "B".equals(well)) return "Marburg";
    if (channel == 4 && ("A".equals(well) || "B".equals(well))) return "IC";
    return channelName(channel);
  }

  private static String channelName(int channel) {
    return List.of("FAM", "VIC", "ROX", "CY5").get(channel - 1);
  }

  private static String runTime(String folder) {
    String[] tokens = folder.split("_");
    return tokens[tokens.length - 3] + "_" + tokens[tokens.length - 2];
  }

  private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  private static byte[] gzip(byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(content);
    }
    return bytes.toByteArray();
  }

  private static byte[] curve() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
    return bytes.toByteArray();
  }
}
