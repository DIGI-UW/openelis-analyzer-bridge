package org.itech.ahb.fhir;

import java.math.BigDecimal;
import java.util.Map;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;

/**
 * Puts the parts the instrument reported on a record in the FHIR slot that exists for each (rules
 * 9, 11 and 17): the number in {@code valueQuantity} with an off-scale comparator, the call as the
 * value or, beside a number, as an interpretation, the instrument's flags as interpretations, the
 * assay as {@code method}, the operator as {@code performer}, notes as {@code note}, and a run that
 * produced nothing as {@code dataAbsentReason}.
 */
final class RecordSlots {

  private static final String ROOT = "https://openelis-global.org/fhir/StructureDefinition/";
  private static final String SUB_ID = "http://hl7.org/fhir/StructureDefinition/observation-v2-subid";
  private static final String CALL_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
  private static final String FLAG_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0078";
  private static final String DATA_ABSENT = "http://terminology.hl7.org/CodeSystem/data-absent-reason";

  /** The HL7 interpretation that states the call an instrument words in its own text. */
  private static final Map<String, String[]> CALLS = Map.of(
    "DETECTED",
    new String[] { "DET", "Detected" },
    "NOT DETECTED",
    new String[] { "ND", "Not detected" },
    "POSITIVE",
    new String[] { "POS", "Positive" },
    "POS",
    new String[] { "POS", "Positive" },
    "NEGATIVE",
    new String[] { "NEG", "Negative" },
    "NEG",
    new String[] { "NEG", "Negative" },
    "INDETERMINATE",
    new String[] { "IND", "Indeterminate" }
  );

  private RecordSlots() {}

  static void apply(Observation observation, AnalyzerResult result) {
    RecordParts parts = result.parts();
    if (!parts.subIdentity().isEmpty()) {
      Extension subId = new Extension(SUB_ID);
      subId.addExtension("original-sub-identifier", new StringType(parts.subIdentity()));
      observation.addExtension(subId);
    }
    if (parts.runFailed()) {
      observation.setDataAbsentReason(new CodeableConcept().addCoding(new Coding(DATA_ABSENT, "error", "Error")));
    } else if (parts.number() != null || parts.limit() != null) {
      Quantity quantity = new Quantity();
      quantity.setValue(new BigDecimal(parts.number() != null ? parts.number() : parts.limit()));
      if (result.units() != null) {
        quantity.setUnit(result.units());
      }
      if ("<".equals(parts.comparator())) {
        quantity.setComparator(Quantity.QuantityComparator.LESS_THAN);
      } else if (">".equals(parts.comparator())) {
        quantity.setComparator(Quantity.QuantityComparator.GREATER_THAN);
      }
      observation.setValue(quantity);
      if (parts.call() != null) {
        observation.addInterpretation(call(parts.call()));
      }
    } else {
      observation.setValue(new StringType(parts.call()));
    }
    parts.flags().forEach(flag -> observation.addInterpretation(flag(flag)));
    if (parts.assayName() != null) {
      CodeableConcept method = new CodeableConcept().setText(parts.assayName());
      if (parts.assayVersion() != null) {
        method.addExtension(ROOT + "analyzer-assay-version", new StringType(parts.assayVersion()));
      }
      observation.setMethod(method);
    }
    if (parts.operator() != null) {
      observation.addPerformer(new Reference().setDisplay(parts.operator()));
    }
    if (parts.instrument() != null) {
      observation.addExtension(ROOT + "analyzer-instrument-identification", new StringType(parts.instrument()));
    }
    parts.notes().forEach(note -> observation.addNote(new Annotation().setText(note)));
  }

  /** The call as an interpretation, with the instrument's own wording as its text. */
  private static CodeableConcept call(String text) {
    CodeableConcept concept = new CodeableConcept().setText(text);
    String[] code = CALLS.get(text.trim().toUpperCase());
    if (code != null) {
      concept.addCoding(new Coding(CALL_SYSTEM, code[0], code[1]));
    }
    return concept;
  }

  /** A flag as sent: an interpretation that states no call, which OpenELIS shows and does not interpret. */
  private static CodeableConcept flag(String flag) {
    return new CodeableConcept().addCoding(new Coding(FLAG_SYSTEM, flag, flag)).setText(flag);
  }
}
