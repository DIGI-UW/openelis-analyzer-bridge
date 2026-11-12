package org.itech.ahb.fhir;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How a profile reads a run exported as one ZIP. The export name's {@code sampleId} group must equal
 * the run record's sample number, and its {@code serial} group must end the {@code serialCell}.
 */
public record FileArchive(
  String resultsEntry,
  String runRecordEntry,
  String sampleIdField,
  String exportNamePattern,
  String serialCell
) {
  public static FileArchive fromProfile(JsonNode profile) {
    JsonNode archive = profile.path("archive");
    if (!archive.isObject()) {
      return null;
    }
    return new FileArchive(
      archive.path("results_entry").asText(),
      archive.path("run_record_entry").asText(),
      archive.path("sample_id_field").asText(),
      archive.path("export_name_pattern").asText(),
      archive.path("serial_cell").asText(null)
    );
  }
}
