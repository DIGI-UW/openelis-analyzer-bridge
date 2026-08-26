package org.itech.ahb.fhir;

import ca.uhn.fhir.context.FhirContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.StringType;
import org.itech.ahb.profile.ControlResultRecognition;

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
    private static final String NORMALIZED_BUNDLE_PROFILE =
            "https://openelis-global.org/fhir/StructureDefinition/analyzer-normalized-bundle-v1";
    private static final String MESSAGE_ID_SYSTEM =
            "https://openelis-global.org/fhir/analyzer-message-id";
    private static final String CONNECTION_ID_SYSTEM =
            "https://openelis-global.org/fhir/analyzer-connection-id";
    private static final String ANALYZER_ID_SYSTEM =
            "https://openelis-global.org/fhir/analyzer-id";
    private static final String RAW_CODE_SYSTEM =
            "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code";
    private static final String EXTENSION_ROOT =
            "https://openelis-global.org/fhir/StructureDefinition/";

    /**
     * Build the versioned Bridge-to-OpenELIS result contract while preserving
     * analyzer-native identity and optional terminology hints independently.
     */
    public static String buildNormalizedBundle(String accessionNumber,
            List<AnalyzerResult> results, AnalyzerContext context,
            java.util.function.Function<String, String> codeToLoinc) {
        Objects.requireNonNull(context, "analyzer context is required");
        String json = buildBaseBundle(accessionNumber, results, context.deviceInfo());
        Bundle bundle = CTX.newJsonParser().parseResource(Bundle.class, json);
        bundle.getMeta().addProfile(NORMALIZED_BUNDLE_PROFILE);
        bundle.getIdentifier().setSystem(MESSAGE_ID_SYSTEM).setValue(UUID.randomUUID().toString());

        Device device = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(Device.class::isInstance)
                .map(Device.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Analyzer source context is required"));
        device.addIdentifier().setSystem(CONNECTION_ID_SYSTEM).setValue(context.bridgeConnectionId());
        device.addIdentifier().setSystem(ANALYZER_ID_SYSTEM).setValue(context.clientAnalyzerId());
        device.addExtension(EXTENSION_ROOT + "analyzer-contract-version", new StringType("1.0"));
        device.addExtension(EXTENSION_ROOT + "analyzer-profile-id", new StringType(context.profileId()));
        device.addExtension(EXTENSION_ROOT + "analyzer-profile-revision",
                new org.hl7.fhir.r4.model.IntegerType(context.profileRevision()));
        device.addExtension(EXTENSION_ROOT + "analyzer-source-protocol",
                new org.hl7.fhir.r4.model.CodeType(context.sourceProtocol()));

        List<Observation> observations = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(Observation.class::isInstance)
                .map(Observation.class::cast)
                .toList();
        if (observations.size() != results.size()) {
            throw new IllegalStateException("Every parsed result must produce one Observation");
        }
        for (int index = 0; index < results.size(); index++) {
            AnalyzerResult result = results.get(index);
            Observation observation = observations.get(index);
            String loinc = codeToLoinc == null ? null : codeToLoinc.apply(result.testCode());
            if (loinc != null && !loinc.isBlank()) {
                observation.getCode().addCoding(new Coding("http://loinc.org", loinc, result.testName()));
            }
            observation.addExtension(EXTENSION_ROOT + "analyzer-raw-value", new StringType(result.value()));
            observation.addExtension(EXTENSION_ROOT + "analyzer-source-transport",
                    new org.hl7.fhir.r4.model.CodeType(context.sourceTransport()));
            observation.addExtension(EXTENSION_ROOT + "analyzer-result-classification",
                    new org.hl7.fhir.r4.model.CodeType(result.isControl() ? "CONTROL" : "PATIENT"));
            observation.addExtension(controlRecognitionExtension(context, result));
        }
        return CTX.newJsonParser().setPrettyPrint(false).encodeResourceToString(bundle);
    }

    private static org.hl7.fhir.r4.model.Extension controlRecognitionExtension(
            AnalyzerContext context, AnalyzerResult result) {
        org.hl7.fhir.r4.model.Extension recognition = new org.hl7.fhir.r4.model.Extension(
                EXTENSION_ROOT + "analyzer-control-recognition");
        recognition.addExtension("mode",
                new org.hl7.fhir.r4.model.CodeType(context.controlRecognition().mode().name()));
        recognition.addExtension("recognitionFingerprint", new StringType(context.recognitionFingerprint()));
        if (context.controlRecognition().mode() == ControlResultRecognition.Mode.NONE) {
            recognition.addExtension("outcome", new org.hl7.fhir.r4.model.CodeType("NOT_EVALUATED"));
            return recognition;
        }
        org.itech.ahb.profile.ControlResultRecognitionEvaluator.Assessment assessment =
                Objects.requireNonNull(result.controlRecognitionAssessment(),
                        "RULES control recognition requires parser evaluation evidence");
        if (assessment.mode() != context.controlRecognition().mode()
                || assessment.outcome()
                        == org.itech.ahb.profile.ControlResultRecognitionEvaluator.Outcome.NOT_EVALUATED) {
            throw new IllegalStateException("Parser recognition evidence does not match the pinned profile mode");
        }
        boolean matched = assessment.outcome()
                == org.itech.ahb.profile.ControlResultRecognitionEvaluator.Outcome.MATCH;
        if (matched != result.isControl()) {
            throw new IllegalStateException("Result classification does not match parser recognition evidence");
        }
        recognition.addExtension("outcome",
                new org.hl7.fhir.r4.model.CodeType(assessment.outcome().name()));
        for (org.itech.ahb.profile.ControlResultRecognitionEvaluator.RuleEvaluation evaluation
                : assessment.evaluations()) {
            if (evaluation.rawValue().isBlank()) {
                throw new IllegalStateException(
                        "RULES control recognition requires the evaluated raw source value");
            }
            org.hl7.fhir.r4.model.Extension evidence = new org.hl7.fhir.r4.model.Extension("evaluation");
            evidence.addExtension("ruleKey", new StringType(evaluation.rule().key()));
            evidence.addExtension("matched",
                    new org.hl7.fhir.r4.model.BooleanType(evaluation.matched()));
            evidence.addExtension("sourceField", new StringType(evaluation.sourceField()));
            evidence.addExtension("rawValue", new StringType(evaluation.rawValue()));
            recognition.addExtension(evidence);
        }
        return recognition;
    }

    private static String buildBaseBundle(String accessionNumber,
            List<AnalyzerResult> results, DeviceInfo deviceInfo) {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        String deviceUrl = "urn:uuid:" + UUID.randomUUID();
        Device device = buildDevice(deviceInfo);
        addEntry(bundle, deviceUrl, device, "Device");

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
            obs.setCode(new CodeableConcept().addCoding(
                    new Coding(RAW_CODE_SYSTEM, result.testCode(), result.testName())));
            obs.setSpecimen(new Reference(specimenUrl));
            obs.setDevice(new Reference(deviceUrl));

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
            // QC metadata for OE QCResultProcessingService.findMatchingControlLot:
            //   - lot-number lets OE strict-match the qc_control_lot row
            //     (sourced from ASTM Q-segment field 3 component 2)
            //   - control-level lets OE level-match (testId, instrumentId, level)
            //     when there's no canonical lot identifier on the wire
            //     (sourced from ASTM Q-segment field 3 component 3, OR from
            //     the matched FILE qcRule's operand for SPECIMEN_ID_PREFIX
            //     rules like LPC/HPC/CNEG/CPOS)
            // Both are optional; OE's resolver falls through tiers gracefully
            // when either is absent.
            if (result.lotNumber() != null && !result.lotNumber().isEmpty()) {
                obs.addExtension(
                        new org.hl7.fhir.r4.model.Extension(
                                "http://openelis-global.org/fhir/qc/lot-number",
                                new org.hl7.fhir.r4.model.StringType(result.lotNumber())));
            }
            if (result.controlLevel() != null && !result.controlLevel().isEmpty()) {
                obs.addExtension(
                        new org.hl7.fhir.r4.model.Extension(
                                "http://openelis-global.org/fhir/qc/control-level",
                                new org.hl7.fhir.r4.model.StringType(result.controlLevel())));
            }
            if (result.controlType() != null && !result.controlType().isEmpty()) {
                obs.addExtension(
                        new org.hl7.fhir.r4.model.Extension(
                                "http://openelis-global.org/fhir/qc/control-type",
                                new org.hl7.fhir.r4.model.StringType(result.controlType())));
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
     * Build a FHIR R4 {@link Device} resource from parsed identification info.
     *
     * <p>Observed source details are retained as evidence only. The durable
     * Bridge connection identifier added by {@link #buildNormalizedBundle}
     * remains the sole routing authority.
     * <ul>
     *   <li>{@code Device.identifier[].value} — sourceId (e.g. source IP),
     *       site code (parsed from sender token), and the raw sender token
     *       itself. OE matches any of them against analyzer identifiers.</li>
     *   <li>{@code Device.deviceName.name} — model component (e.g. "GeneXpert").
     *       OE falls back to this for analyzer-by-name match.</li>
     *   <li>{@code Device.manufacturer} — derived best-effort from sender
     *       token; OE may use it for profile selection.</li>
     *   <li>{@code Device.version[].value} — version component when parseable
     *       (e.g. "6.2"). Operational metadata.</li>
     * </ul>
     */
    private static Device buildDevice(DeviceInfo info) {
        Device device = new Device();

        // Identifiers — every distinct value gets its own entry. OE walks
        // them with tryFindAnalyzerByIdentifier(List<String>).
        if (info.sourceId() != null && !info.sourceId().isBlank()) {
            device.addIdentifier()
                .setSystem("https://openelis-global.org/fhir/source-ip")
                .setValue(info.sourceId());
        }
        if (info.senderToken() != null && !info.senderToken().isBlank()) {
            device.addIdentifier()
                .setSystem("https://openelis-global.org/fhir/sender-token")
                .setValue(info.senderToken());
        }
        if (info.site() != null && !info.site().isBlank()) {
            device.addIdentifier()
                .setSystem("https://openelis-global.org/fhir/site")
                .setValue(info.site());
        }

        // Device name — model when parseable
        if (info.model() != null && !info.model().isBlank()) {
            Device.DeviceDeviceNameComponent dn = new Device.DeviceDeviceNameComponent();
            dn.setName(info.model());
            dn.setType(Device.DeviceNameType.MANUFACTURERNAME);
            device.addDeviceName(dn);
        } else if (info.senderToken() != null && !info.senderToken().isBlank()) {
            // Fall back to the raw sender token if model wasn't isolated
            Device.DeviceDeviceNameComponent dn = new Device.DeviceDeviceNameComponent();
            dn.setName(info.senderToken());
            dn.setType(Device.DeviceNameType.MANUFACTURERNAME);
            device.addDeviceName(dn);
        }

        if (info.manufacturer() != null && !info.manufacturer().isBlank()) {
            device.setManufacturer(info.manufacturer());
        }

        if (info.version() != null && !info.version().isBlank()) {
            Device.DeviceVersionComponent v = new Device.DeviceVersionComponent();
            v.setValue(info.version());
            device.addVersion(v);
        }

        return device;
    }

    /**
     * Identification info parsed by the bridge from a protocol's sender
     * field, packaged for inclusion in the FHIR Bundle as a Device resource.
     *
     * <p>All fields are nullable; the builder preserves whatever source evidence
     * is present without using it to resolve a connection.
     *
     * @param sourceId    network address of the analyzer (e.g. "10.0.0.42")
     * @param senderToken raw sender token from the protocol header
     *                    (e.g. ASTM H-record field 5: "LA2M3^GeneXpert^6.2";
     *                    HL7 MSH-3: "MINDRAY")
     * @param site        parsed site code (e.g. "LA2M3"); null if not parseable
     * @param model       parsed model name (e.g. "GeneXpert"); null if not parseable
     * @param version     parsed version (e.g. "6.2"); null if not parseable
     * @param manufacturer best-effort manufacturer name (e.g. "Cepheid"); may be null
     */
    public record DeviceInfo(
            String sourceId,
            String senderToken,
            String site,
            String model,
            String version,
            String manufacturer) {

        /** Build from just a sourceId + a raw sender token; parses the token as ASTM-style {@code site^model^version}. */
        public static DeviceInfo fromSenderToken(String sourceId, String senderToken) {
            String site = null, model = null, version = null;
            if (senderToken != null && !senderToken.isBlank() && senderToken.contains("^")) {
                String[] parts = senderToken.split("\\^", -1);
                if (parts.length >= 1) site = blankToNull(parts[0]);
                if (parts.length >= 2) model = blankToNull(parts[1]);
                if (parts.length >= 3) version = blankToNull(parts[2]);
            } else if (senderToken != null && !senderToken.isBlank()) {
                // Single-token sender (e.g. "MINDRAY") — treat as model.
                model = senderToken.trim();
            }
            return new DeviceInfo(sourceId, senderToken, site, model, version, null);
        }

        private static String blankToNull(String s) {
            return (s == null || s.trim().isEmpty()) ? null : s.trim();
        }
    }

    /** Exact saved-connection and source context for one normalized message. */
    public record AnalyzerContext(
            String bridgeConnectionId,
            String clientAnalyzerId,
            String profileId,
            int profileRevision,
            String sourceProtocol,
            String sourceTransport,
            DeviceInfo deviceInfo,
            ControlResultRecognition controlRecognition,
            String recognitionFingerprint) {

        public AnalyzerContext {
            requireNonBlank(bridgeConnectionId, "Bridge connection ID");
            requireNonBlank(clientAnalyzerId, "OpenELIS analyzer ID");
            requireNonBlank(profileId, "Profile ID");
            if (profileRevision < 1) {
                throw new IllegalArgumentException("Profile revision is required");
            }
            requireNonBlank(sourceProtocol, "Source protocol");
            requireNonBlank(sourceTransport, "Source transport");
            Objects.requireNonNull(deviceInfo, "Device source context is required");
            Objects.requireNonNull(controlRecognition, "Control recognition is required");
            if (recognitionFingerprint == null
                    || !recognitionFingerprint.matches("^sha256:[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("Recognition fingerprint is required");
            }
        }

        private static void requireNonBlank(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " is required");
            }
        }
    }

    /**
     * A single test result from an analyzer, protocol-agnostic.
     *
     * For QC samples, two additional metadata fields propagate end-to-end
     * to OE so QCResultProcessingService can resolve to the correct lot
     * without guessing:
     *   - lotNumber: canonical lot identifier (from ASTM Q-segment field 3
     *     component 2, or substring-extracted from FILE sample-name when
     *     the operator embedded it)
     *   - controlLevel: clinical level identifier (LPC/HPC/CNEG/CPOS/etc.)
     *     — sourced from ASTM Q-segment field 3 component 3, or from the
     *     FILE qcRule SPECIMEN_ID_PREFIX operand that matched
     */
    public record AnalyzerResult(
            String testCode,
            String testName,
            String value,
            String units,
            boolean isNumeric,
            boolean isControl,
            String timestamp,
            String lotNumber,
            String controlLevel,
            String controlType,
            org.itech.ahb.profile.ControlResultRecognitionEvaluator.Assessment controlRecognitionAssessment) {

        public static AnalyzerResult numeric(String testCode, String testName, String value, String units) {
            return new AnalyzerResult(testCode, testName, value, units, true, false,
                    null, null, null, null, null);
        }

        public static AnalyzerResult text(String testCode, String testName, String value) {
            return new AnalyzerResult(testCode, testName, value, null, false, false,
                    null, null, null, null, null);
        }

        public AnalyzerResult withControl(boolean control) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, control,
                    timestamp, lotNumber, controlLevel, controlType, controlRecognitionAssessment);
        }

        public AnalyzerResult withTimestamp(String ts) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl,
                    ts, lotNumber, controlLevel, controlType, controlRecognitionAssessment);
        }

        public AnalyzerResult withLotNumber(String lot) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl,
                    timestamp, lot, controlLevel, controlType, controlRecognitionAssessment);
        }

        public AnalyzerResult withControlLevel(String level) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl,
                    timestamp, lotNumber, level, controlType, controlRecognitionAssessment);
        }

        public AnalyzerResult withControlType(String type) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl,
                    timestamp, lotNumber, controlLevel, type, controlRecognitionAssessment);
        }

        public AnalyzerResult withControlRecognition(
                org.itech.ahb.profile.ControlResultRecognitionEvaluator.Assessment assessment) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl,
                    timestamp, lotNumber, controlLevel, controlType, assessment);
        }
    }
}
