package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import java.math.BigDecimal;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.StringType;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FhirBundleBuilder}.
 *
 * <p>Validates FHIR R4 transaction Bundle construction: resource types,
 * Specimen identifiers, Observation codes/values, QC tagging, timestamp
 * handling, and internal reference consistency.
 */
@DisplayName("FhirBundleBuilder")
class FhirBundleBuilderTest {

    private static final FhirContext CTX = FhirContext.forR4();
    private static final String ACCESSION = "ACC-2026-001";
    private static final String ANALYZER_ID = "MINDRAY-BC5380";

    private Bundle parseBundle(String json) {
        return CTX.newJsonParser().parseResource(Bundle.class, json);
    }

    @Nested
    @DisplayName("Bundle structure")
    class BundleStructure {

        @Test
        @DisplayName("Should produce valid JSON parseable by HAPI FhirContext")
        void shouldProduceValidJson() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            String json = FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results);

            assertNotNull(json);
            assertDoesNotThrow(() -> parseBundle(json));
        }

        @Test
        @DisplayName("Bundle type should be TRANSACTION")
        void bundleTypeShouldBeTransaction() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            assertEquals(Bundle.BundleType.TRANSACTION, bundle.getType());
        }

        @Test
        @DisplayName("Should contain Specimen, Observation(s), and DiagnosticReport")
        void shouldContainExpectedResourceTypes() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"),
                    AnalyzerResult.text("INTERP", "INTERP", "Normal"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            long specimenCount = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Specimen).count();
            long observationCount = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation).count();
            long reportCount = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof DiagnosticReport).count();

            assertEquals(1, specimenCount, "Should have exactly 1 Specimen");
            assertEquals(2, observationCount, "Should have 1 Observation per result");
            assertEquals(1, reportCount, "Should have exactly 1 DiagnosticReport");
        }

        @Test
        @DisplayName("All entries should have POST request method")
        void allEntriesShouldHavePostMethod() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                assertEquals(Bundle.HTTPVerb.POST, entry.getRequest().getMethod());
            }
        }
    }

    @Nested
    @DisplayName("Specimen")
    class SpecimenTests {

        @Test
        @DisplayName("Specimen.identifier matches accession number")
        void specimenIdentifierMatchesAccession() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Specimen specimen = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Specimen)
                    .map(e -> (Specimen) e.getResource())
                    .findFirst().orElseThrow();

            assertEquals(ACCESSION, specimen.getIdentifierFirstRep().getValue());
        }
    }

    @Nested
    @DisplayName("Observation values")
    class ObservationValues {

        @Test
        @DisplayName("Observation.code matches test code")
        void observationCodeMatchesTestCode() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "White Blood Cells", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            Coding coding = obs.getCode().getCodingFirstRep();
            assertEquals("WBC", coding.getCode());
            assertEquals("White Blood Cells", coding.getDisplay());
        }

        @Test
        @DisplayName("Numeric value -> valueQuantity with correct value and unit")
        void numericValueProducesQuantity() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            assertTrue(obs.getValue() instanceof Quantity);
            Quantity qty = (Quantity) obs.getValue();
            assertEquals(0, new BigDecimal("7.5").compareTo(qty.getValue()));
            assertEquals("10*3/uL", qty.getUnit());
        }

        @Test
        @DisplayName("Text value -> valueString")
        void textValueProducesString() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.text("INTERP", "Interpretation", "Normal"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            assertTrue(obs.getValue() instanceof StringType);
            assertEquals("Normal", ((StringType) obs.getValue()).getValue());
        }

        @Test
        @DisplayName("Numeric value with null units -> Quantity without unit")
        void numericWithNullUnits() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("RATIO", "RATIO", "1.5", null));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            Quantity qty = (Quantity) obs.getValue();
            assertEquals(0, new BigDecimal("1.5").compareTo(qty.getValue()));
            assertNull(qty.getUnit());
        }
    }

    @Nested
    @DisplayName("QC tagging")
    class QcTagging {

        @Test
        @DisplayName("QC result -> Meta tag with 'QC' code")
        void qcResultHasMetaTag() {
            AnalyzerResult qcResult = AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL")
                    .withControl(true);

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, List.of(qcResult)));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            List<Coding> tags = obs.getMeta().getTag();
            assertFalse(tags.isEmpty(), "QC observation should have meta tags");
            assertTrue(tags.stream().anyMatch(t ->
                            "QC".equals(t.getCode())
                            && "http://openelis-global.org/fhir/tags".equals(t.getSystem())),
                    "Should have QC tag with correct system");
        }

        @Test
        @DisplayName("Non-QC result -> no Meta tag")
        void nonQcResultHasNoMetaTag() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            assertTrue(obs.getMeta().getTag().isEmpty(),
                    "Non-QC observation should have no meta tags");
        }
    }

    @Nested
    @DisplayName("Timestamp handling")
    class TimestampHandling {

        @Test
        @DisplayName("Valid timestamp -> effectiveDateTime set")
        void validTimestampSetsEffective() {
            AnalyzerResult result = AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL")
                    .withTimestamp("2026-03-26T12:00:00");

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, List.of(result)));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            assertNotNull(obs.getEffective(), "effectiveDateTime should be set");
        }

        @Test
        @DisplayName("Null timestamp -> no effectiveDateTime")
        void nullTimestampNoEffective() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            assertNull(obs.getEffective(), "effectiveDateTime should not be set");
        }
    }

    @Nested
    @DisplayName("Internal reference consistency")
    class ReferenceConsistency {

        @Test
        @DisplayName("DiagnosticReport.result references match Observation fullUrls")
        void reportResultRefsMatchObservationUrls() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"),
                    AnalyzerResult.numeric("RBC", "RBC", "4.82", "10*6/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            // Collect Observation fullUrls
            List<String> obsUrls = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(Bundle.BundleEntryComponent::getFullUrl)
                    .toList();

            // Collect DiagnosticReport result references
            DiagnosticReport report = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof DiagnosticReport)
                    .map(e -> (DiagnosticReport) e.getResource())
                    .findFirst().orElseThrow();

            List<String> reportRefs = report.getResult().stream()
                    .map(Reference::getReference)
                    .toList();

            assertEquals(obsUrls.size(), reportRefs.size(),
                    "Report should reference all observations");
            assertTrue(obsUrls.containsAll(reportRefs),
                    "All report references should match observation fullUrls");
        }

        @Test
        @DisplayName("Observation.specimen references match Specimen fullUrl")
        void observationSpecimenRefMatchesSpecimenUrl() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            String specimenUrl = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Specimen)
                    .map(Bundle.BundleEntryComponent::getFullUrl)
                    .findFirst().orElseThrow();

            Observation obs = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Observation)
                    .map(e -> (Observation) e.getResource())
                    .findFirst().orElseThrow();

            assertEquals(specimenUrl, obs.getSpecimen().getReference(),
                    "Observation specimen reference should match Specimen fullUrl");
        }

        @Test
        @DisplayName("DiagnosticReport.specimen references match Specimen fullUrl")
        void reportSpecimenRefMatchesSpecimenUrl() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            String specimenUrl = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof Specimen)
                    .map(Bundle.BundleEntryComponent::getFullUrl)
                    .findFirst().orElseThrow();

            DiagnosticReport report = bundle.getEntry().stream()
                    .filter(e -> e.getResource() instanceof DiagnosticReport)
                    .map(e -> (DiagnosticReport) e.getResource())
                    .findFirst().orElseThrow();

            assertEquals(specimenUrl, report.getSpecimenFirstRep().getReference(),
                    "DiagnosticReport specimen reference should match Specimen fullUrl");
        }

        @Test
        @DisplayName("All fullUrls use urn:uuid: scheme")
        void allFullUrlsUseUrnUuid() {
            List<AnalyzerResult> results = List.of(
                    AnalyzerResult.numeric("WBC", "WBC", "7.5", "10*3/uL"));

            Bundle bundle = parseBundle(
                    FhirBundleBuilder.buildBundle(ACCESSION, ANALYZER_ID, results));

            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                assertTrue(entry.getFullUrl().startsWith("urn:uuid:"),
                        "fullUrl should use urn:uuid: scheme, got: " + entry.getFullUrl());
            }
        }
    }
}
