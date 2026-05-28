package org.itech.ahb.fhir;

import ca.uhn.fhir.context.FhirContext;
import java.math.BigDecimal;
import java.util.List;
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
     * <p>Backwards-compatible overload — no Device resource included. Call the
     * {@link #buildBundle(String, String, List, DeviceInfo)} overload instead
     * when the bridge can supply identification (sourceId + protocol sender
     * token), so OE can find-or-create the analyzer stub atomically per
     * the transparent-pipe architecture.
     *
     * @param accessionNumber the sample/accession ID
     * @param analyzerId      the OE analyzer ID (may be null if unknown)
     * @param results         list of individual test results
     * @return FHIR Bundle JSON string ready for POST to OE /analyzer/fhir
     */
    public static String buildBundle(String accessionNumber, String analyzerId,
            List<AnalyzerResult> results) {
        return buildBundle(accessionNumber, analyzerId, results, null);
    }

    /**
     * Build a FHIR R4 transaction Bundle including a {@link Device} resource
     * for analyzer identification.
     *
     * <p>The Device resource carries identification fields parsed by the
     * bridge from the protocol's sender token (ASTM H-record field 5,
     * HL7 MSH-3, etc.). OE's import controller reads it to find-or-create
     * the analyzer stub atomically — so even if {@code analyzerId} is
     * null (unregistered source), results still land in OE staging
     * under a freshly-created PENDING_REGISTRATION stub.
     *
     * @param accessionNumber the sample/accession ID
     * @param analyzerId      the OE analyzer ID; may be null if the bridge
     *                        couldn't resolve the source — OE will use the
     *                        Device resource to find-or-create
     * @param results         list of individual test results
     * @param deviceInfo      identification parsed by the bridge; may be null
     *                        for backwards compat (no Device resource added)
     * @return FHIR Bundle JSON string ready for POST to OE /analyzer/fhir
     */
    public static String buildBundle(String accessionNumber, String analyzerId,
            List<AnalyzerResult> results, DeviceInfo deviceInfo) {
        return buildBundle(accessionNumber, analyzerId, results, deviceInfo, null);
    }

    /**
     * As {@link #buildBundle(String, String, List, DeviceInfo)} but translates
     * each result's analyzer test code to LOINC via {@code codeToLoinc}, so the
     * emitted Observations are LOINC-coded. This lets OE2 resolve results by
     * LOINC (its external-order FHIR path) and stay analyzer-agnostic. A code
     * with no LOINC mapping falls back to the raw code with no coding system —
     * it still flows, and the absent LOINC system flags it as unmapped.
     *
     * @param codeToLoinc analyzer code → LOINC resolver; null preserves the
     *                    legacy raw-code behavior
     */
    public static String buildBundle(String accessionNumber, String analyzerId,
            List<AnalyzerResult> results, DeviceInfo deviceInfo,
            java.util.function.Function<String, String> codeToLoinc) {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        // Device — analyzer identification (when bridge could parse it).
        // OE's AnalyzerFhirImportController uses Device.identifier and
        // Device.deviceName to resolve the analyzer (and create a stub
        // when no existing analyzer matches).
        String deviceUrl = null;
        if (deviceInfo != null && deviceInfo.hasAnyIdentifier()) {
            deviceUrl = "urn:uuid:" + UUID.randomUUID();
            Device device = buildDevice(deviceInfo);
            addEntry(bundle, deviceUrl, device, "Device");
        }

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
            // Code the Observation in LOINC when the analyzer code maps (the
            // bridge owns analyzer↔LOINC; OE2 resolves by LOINC). Unmapped or
            // no-resolver → keep the raw analyzer code with no system so it
            // still flows and OE can flag it.
            Coding obsCoding = new Coding().setDisplay(result.testName());
            String loinc = codeToLoinc != null ? codeToLoinc.apply(result.testCode()) : null;
            if (loinc != null && !loinc.isBlank()) {
                obsCoding.setSystem("http://loinc.org").setCode(loinc);
            } else {
                obsCoding.setCode(result.testCode());
            }
            obs.setCode(new CodeableConcept().addCoding(obsCoding));
            obs.setSpecimen(new Reference(specimenUrl));
            // Link to Device when present — gives OE a per-observation
            // pointer to the analyzer that produced the result.
            if (deviceUrl != null) {
                obs.setDevice(new Reference(deviceUrl));
            }

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
     * <p>Mappings (chosen so OE's existing AnalyzerFhirImportController
     * Device-resolution code path uses them out of the box):
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
     * <p>All fields are nullable; the builder uses whatever's present.
     * {@code sourceId} (network source IP/host) should always be set
     * — it's the most stable identifier for re-routing.
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

        /** True if any identifier field is non-blank — bundle-builder uses this to decide whether to emit a Device entry. */
        public boolean hasAnyIdentifier() {
            return (sourceId != null && !sourceId.isBlank())
                || (senderToken != null && !senderToken.isBlank())
                || (model != null && !model.isBlank());
        }

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
            String controlLevel) {

        public static AnalyzerResult numeric(String testCode, String testName, String value, String units) {
            return new AnalyzerResult(testCode, testName, value, units, true, false, null, null, null);
        }

        public static AnalyzerResult text(String testCode, String testName, String value) {
            return new AnalyzerResult(testCode, testName, value, null, false, false, null, null, null);
        }

        public AnalyzerResult withControl(boolean control) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, control, timestamp, lotNumber, controlLevel);
        }

        public AnalyzerResult withTimestamp(String ts) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl, ts, lotNumber, controlLevel);
        }

        public AnalyzerResult withLotNumber(String lot) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl, timestamp, lot, controlLevel);
        }

        public AnalyzerResult withControlLevel(String level) {
            return new AnalyzerResult(testCode, testName, value, units, isNumeric, isControl, timestamp, lotNumber, level);
        }
    }
}
