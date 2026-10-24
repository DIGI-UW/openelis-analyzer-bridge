package org.itech.ahb.fhir;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.StringType;
import org.itech.ahb.profile.ResultReading;

/** The Bridge finishes vendor spelling interpretation; OE only maps the canonical answer. */
final class NormalizedAnswer {

  static final String ROLE = "https://openelis-global.org/fhir/StructureDefinition/analyzer-result-part";
  static final String ANSWER = "https://openelis-global.org/fhir/StructureDefinition/analyzer-canonical-answer";

  private NormalizedAnswer() {}

  static void apply(Observation observation, FhirBundleBuilder.AnalyzerResult result, ResultReading reading) {
    String sub = result.parts() == null ? "" : result.parts().subIdentity();
    if (reading != null) {
      for (var coding : reading.definition(result.testCode(), sub).path("codings")) {
        String system = coding.path("system").asText();
        String code = coding.path("code").asText();
        if (
          !system.isBlank() &&
          !code.isBlank() &&
          observation
            .getCode()
            .getCoding()
            .stream()
            .noneMatch(existing -> system.equals(existing.getSystem()) && code.equals(existing.getCode()))
        ) {
          observation.getCode().addCoding(new Coding(system, code, coding.path("display").asText(null)));
        }
      }
    }
    if (observation.hasDataAbsentReason()) return;
    String raw = result.parts() != null ? result.parts().call() : result.isNumeric() ? null : result.value();
    if (raw == null) return;
    String canonical = reading == null ? raw : reading.canonicalAnswer(result.testCode(), sub, raw);
    observation.addExtension(ANSWER, new StringType(canonical));
    CodeableConcept meaning = new CodeableConcept().setText(canonical);
    if (reading != null) {
      for (var coding : reading.definition(result.testCode(), sub).path("value_codes").path(canonical)) {
        if (coding.hasNonNull("system") && coding.hasNonNull("code")) {
          meaning.addCoding(
            new Coding(
              coding.path("system").asText(),
              coding.path("code").asText(),
              coding.path("display").asText(null)
            )
          );
        }
      }
    }
    if (observation.hasValueQuantity()) {
      // RecordSlots emits the call before any informational flags. Mark it explicitly even when
      // no standard interpretation code exists, so it cannot be mistaken for a flag.
      CodeableConcept call = observation.getInterpretationFirstRep();
      call.setText(canonical);
      meaning.getCoding().forEach(call::addCoding);
    } else {
      observation.setValue(meaning.hasCoding() ? meaning : new StringType(canonical));
    }
  }
}
