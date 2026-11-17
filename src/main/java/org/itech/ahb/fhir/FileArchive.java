package org.itech.ahb.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a profile reads a run exported as one ZIP. The export name's {@code sampleId} group must equal
 * the run record's sample number, and each group in {@code nameCells} must end its cell of the result sheet.
 */
public record FileArchive(
  String resultsEntry,
  String runRecordEntry,
  String sampleIdField,
  String exportNamePattern,
  Map<String, String> nameCells
) {
  public static FileArchive fromProfile(JsonNode profile) {
    JsonNode archive = profile.path("archive");
    if (!archive.isObject()) {
      return null;
    }
    Map<String, String> nameCells = new LinkedHashMap<>();
    archive.path("name_cells").fields().forEachRemaining(cell -> nameCells.put(cell.getKey(), cell.getValue().asText()));
    return new FileArchive(
      archive.path("results_entry").asText(),
      archive.path("run_record_entry").asText(),
      archive.path("sample_id_field").asText(),
      archive.path("export_name_pattern").asText(),
      nameCells
    );
  }
}
