package org.itech.ahb.fhir;

import ca.uhn.fhir.context.FhirContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.StringType;

/**
 * Builds FHIR R4 transaction Bundles from parsed analyzer results.
 *
 * <p>Used by the bridge to normalize all analyzer protocols (HL7 v2, ASTM,
 * Excel, CSV) into a standard FHIR payload before forwarding to OE.
 *
 * <p>Bundle structure:
 * <ul>
 *   <li>One Specimen per accession number (carries the sample ID)</li>
 *   <li>One Observation per test result (references the Specimen)</li>
 *   <li>One DiagnosticReport grouping all Observations</li>
 * </ul>
 */
public class FhirBundleBuilder {

    private static final FhirContext CTX = FhirContext.forR4();

    /**
     * Build a FHIR R4 transaction Bundle from a list of parsed results.
     *
     * @param accessionNumber the sample/accession ID
     * @param analyzerId      the OE analyzer ID (from bridge registry)
     * @param results         list of individual test results
     * @return FHIR Bundle JSON string ready for POST to OE /analyzer/fhir
     */
    public static String buildBundle(String accessionNumber, String analyzerId,
            List<AnalyzerResult> results) {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        // Specimen — carries the accession number
        String specimenUrl = "urn:uuid:" + UUID.randomUUID();
        Specimen specimen = new Specimen();
        specimen.addIdentifier().setValue(accessionNumber);
        addEntry(bundle, specimenUrl, specimen, "Specimen");

        // DiagnosticReport — groups all observations
        String reportUrl = "urn:uuid:" + UUID.randomUUID();
        DiagnosticReport report = new DiagnosticReport();
        report.setStatus(DiagnosticReport.DiagnosticReportStatus.PRELIMINARY);
        report.addCategory(new CodeableConcept()
                .addCoding(new Coding("http://terminology.hl7.org/CodeSystem/v2-0074", "LAB", "Laboratory")));
        report.setCode(new CodeableConcept().setText("Analyzer Results"));
        report.addSpecimen(new Reference(specimenUrl));

        // Observations — one per test result
        for (AnalyzerResult result : results) {
            String obsUrl = "urn:uuid:" + UUID.randomUUID();

            Observation obs = new Observation();
            obs.setStatus(Observation.ObservationStatus.PRELIMINARY);
            obs.addCategory(new CodeableConcept()
                    .addCoding(new Coding(
                            "http://terminology.hl7.org/CodeSystem/observation-category",
                            "laboratory", "Laboratory")));
            obs.setCode(new CodeableConcept()
                    .addCoding(new Coding().setCode(result.testCode()).setDisplay(result.testName())));
            obs.setSpecimen(new Reference(specimenUrl));

            // Set value based on type
            if (result.isNumeric()) {
                Quantity qty = new Quantity();
                qty.setValue(new BigDecimal(result.value()));
                if (result.units() != null) {
                    qty.setUnit(result.units());
                }
                obs.setValue(qty);
            } else {
                obs.setValue(new StringType(result.value()));
            }

            // QC/control flag — OE uses this to route to QC queue
            if (result.isControl()) {
                obs.getMeta().addTag("http://openelis-global.org/fhir/tags", "QC", "Quality Control");
            }

            // Analyzer completion timestamp
            if (result.timestamp() != null && !result.timestamp().isBlank()) {
                try {
                    obs.setEffective(new org.hl7.fhir.r4.model.DateTimeType(result.timestamp()));
                } catch (Exception ignored) {
                    // Timestamp format not parseable — skip
                }
            }

            addEntry(bundle, obsUrl, obs, "Observation");
            report.addResult(new Reference(obsUrl));
        }

        addEntry(bundle, reportUrl, report, "DiagnosticReport");

        return CTX.newJsonParser().setPrettyPrint(false).encodeResourceToString(bundle);
    }

    private static void addEntry(Bundle bundle, String fullUrl,
            org.hl7.fhir.r4.model.Resource resource, String resourceType) {
        bundle.addEntry()
                .setFullUrl(fullUrl)
                .setResource(resource)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.POST)
                .setUrl(resourceType);
    }

    /**
     * A single test result from an analyzer, protocol-agnostic.
     */
    public record AnalyzerResult(
            String testCode,
            String testName,
            String value,
            String units,
            boolean isNumeric,
            boolean isControl,
            String timestamp) {

        public static AnalyzerResult numeric(String testCode, String testName, String value, String units) {
            return new AnalyzerResult(testCode, testName, value, units, true, false, null);
        }

        public static AnalyzerResult text(String testCode, String testName, String value) {
            return new AnalyzerResult(testCode, testName, value, null, false, false, null);
        }

        public AnalyzerResult withControl(boolean control) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, control, timestamp);
        }

        public AnalyzerResult withTimestamp(String ts) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl, ts);
        }
    }
}
