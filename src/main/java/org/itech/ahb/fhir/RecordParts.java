package org.itech.ahb.fhir;

import java.util.ArrayList;
import java.util.List;

/**
 * Every part the instrument reported on one result record, as the profile's result parts locate
 * them. The bundle puts each in its FHIR slot; nothing here is interpreted.
 *
 * @param subIdentity the analyte and complementary names in the vendor's sub-ID notation
 *     ({@code HIV-1&Ct}, {@code &LOG}); empty for the main result
 * @param call the qualitative part (DETECTED, POS), as sent
 * @param number the quantitative part, as the instrument wrote it
 * @param comparator {@code <} or {@code >} when the flag says the result is off scale
 * @param limit the range limit the comparator points at, when the instrument sent no number
 * @param range the reference range as sent
 * @param flags the instrument's flags as sent, except an off-scale comparator
 * @param instrument the instrument identification as sent
 * @param status the result status the instrument gave the record (ASTM R.9), as sent
 * @param runFailed whether the profile says this value means the run produced no result
 * @param notes comments and error detail attached to the record
 */
public record RecordParts(
  String subIdentity,
  String call,
  String number,
  String comparator,
  String limit,
  String range,
  List<String> flags,
  String assayName,
  String assayVersion,
  String operator,
  String instrument,
  String status,
  boolean runFailed,
  List<String> notes
) {
  public RecordParts {
    subIdentity = subIdentity == null ? "" : subIdentity;
    flags = flags == null ? List.of() : List.copyOf(flags);
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  public RecordParts withNote(String note) {
    List<String> all = new ArrayList<>(notes);
    all.add(note);
    return new RecordParts(
      subIdentity,
      call,
      number,
      comparator,
      limit,
      range,
      flags,
      assayName,
      assayVersion,
      operator,
      instrument,
      status,
      runFailed,
      all
    );
  }
}
