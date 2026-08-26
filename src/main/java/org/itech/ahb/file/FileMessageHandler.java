package org.itech.ahb.file;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.config.FhirRoutingConfig;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.fhir.FileResultParser;
import org.itech.ahb.fhir.FhirBundleBuilder;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Handles file-based analyzer messages.
 *
 * FILE transport now uses the same OpenELIS forwarding configuration as the
 * other bridge transports. Files are parsed into FHIR and posted to the
 * shared `/analyzer/fhir` endpoint; there is no separate direct-import path.
 */
@Component
@Slf4j
public class FileMessageHandler {

    private final FhirRoutingConfig fhirConfig;
    private final AnalyzerRuntimeRegistry registry;
    private final HTTPForwardServerConfigurationProperties httpConfig;

    private volatile HttpClient httpClient;

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

    @Autowired
    public FileMessageHandler(@Autowired(required = false) FhirRoutingConfig fhirConfig,
            @Autowired(required = false) AnalyzerRuntimeRegistry registry,
            HTTPForwardServerConfigurationProperties httpConfig) {
        this.fhirConfig = fhirConfig;
        this.registry = registry;
        this.httpConfig = httpConfig;
    }

    private HttpClient httpClient() {
        HttpClient existing = httpClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = org.itech.ahb.util.HttpClientFactory.create(
                        httpConfig.getConnectTimeoutSeconds(), httpConfig.isInsecureTls(), "file-import");
            }
            return httpClient;
        }
    }

    public MessageEnvelope processFile(Path filePath, String analyzerId) throws IOException, FileProcessingException {
        return processFile(filePath, analyzerId, null);
    }

    /**
     * Callback for per-accession progress during file processing.
     * Used by the upload controller to stream progress to the browser.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onAccession(int current, int total, String accessionNumber);
    }

    /**
     * Process a file with an optional event-scoped per-file test code that
     * the parser applies to rows lacking a per-row testCode from the
     * column mapping. An explicit caller value (for example, an admin upload)
     * overrides the single file-wide test code materialized from the pinned
     * Bridge profile for unattended watcher processing.
     */
    public MessageEnvelope processFile(Path filePath, String analyzerId, String perFileTestCode)
            throws IOException, FileProcessingException {
        return processFile(filePath, analyzerId, perFileTestCode, null);
    }

    public MessageEnvelope processFile(Path filePath, String analyzerId, String perFileTestCode,
            ProgressCallback progress) throws IOException, FileProcessingException {
        if (analyzerId == null || analyzerId.isBlank()) {
            throw new FileProcessingException("analyzerId is required for FILE delivery: " + filePath);
        }

        long fileSize = Files.size(filePath);
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            log.warn("File size ({} bytes) exceeds max ({}), processing with caution", fileSize, MAX_FILE_SIZE_BYTES);
        }
        if (fileSize == 0) {
            throw new FileProcessingException("File is empty: " + filePath);
        }

        byte[] content = Files.readAllBytes(filePath);
        if (content.length == 0) {
            throw new FileProcessingException("File is empty: " + filePath);
        }

        if (fhirConfig == null || !fhirConfig.isUseFhir()) {
            throw new FileProcessingException(
                    "FILE transport requires bridge.routing.useFhir=true; legacy direct import path has been removed");
        }

        postFileAsFhir(filePath, analyzerId, content, perFileTestCode, progress);

        return MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId(filePath.toString())
                .rawMessage(filePath.getFileName().toString())
                .resolvedAnalyzerId(analyzerId)
                .analyzerId(analyzerId)
                .build();
    }

    /** Build one normalized FILE result bundle from the pinned saved connection. */
    static String buildFileFhirBundle(AnalyzerEntry analyzerEntry,
            HL7ResultParser.ParsedResults parsed, String sourceFile) {
        Objects.requireNonNull(analyzerEntry, "analyzerEntry is required");
        java.util.function.Function<String, String> codeToLoinc = analyzerEntry::getLoincForCode;
        FhirBundleBuilder.AnalyzerContext context = new FhirBundleBuilder.AnalyzerContext(
                analyzerEntry.getBridgeConnectionId(),
                analyzerEntry.getId(),
                analyzerEntry.getProfileId(),
                analyzerEntry.getProfileRevision(),
                "FILE",
                "FILE",
                FhirBundleBuilder.DeviceInfo.fromSenderToken(sourceFile, analyzerEntry.getName()),
                analyzerEntry.getControlResultRecognition(),
                analyzerEntry.getRecognitionFingerprint());
        return FhirBundleBuilder.buildNormalizedBundle(
                parsed.accessionNumber(), parsed.results(), context, codeToLoinc);
    }

    private void postFileAsFhir(Path filePath, String analyzerId, byte[] content, String perFileTestCode,
            ProgressCallback progress)
            throws IOException, FileProcessingException {
        // Resolve analyzer entry from registry
        AnalyzerEntry analyzerEntry = null;
        if (registry != null) {
            for (Map.Entry<String, AnalyzerEntry> entry : registry.getRegisteredAnalyzers().entrySet()) {
                if (analyzerId.equals(entry.getValue().getId())) {
                    analyzerEntry = entry.getValue();
                    break;
                }
            }
        }
        String effectiveFileTestCode = resolveFileTestCode(analyzerEntry, perFileTestCode);

        Map<String, String> columnMappings = analyzerEntry != null ? analyzerEntry.getColumnMappings() : null;
        if (columnMappings == null || columnMappings.isEmpty()) {
            throw new FileProcessingException(
                    "No column mappings registered for analyzer " + analyzerId + " — refusing FILE fallback");
        }

        ControlResultRecognition recognition = analyzerEntry.getControlResultRecognition();
        if (recognition == null) {
            throw new FileProcessingException(
                    "Analyzer " + analyzerId
                    + " has no control-result recognition from its pinned profile");
        }
        TabularResultValueSelection resultSelection =
                analyzerEntry.getTabularResultValueSelection();
        if (resultSelection == null) {
            throw new FileProcessingException(
                    "Analyzer " + analyzerId
                            + " has no result-value selection from its pinned profile");
        }

        // Dispatch by file extension: CSV/TSV/TXT → CSV parser, XLS/XLSX → Excel parser
        String ext = getFileExtension(filePath);
        List<HL7ResultParser.ParsedResults> allResults;

        if (".csv".equals(ext) || ".tsv".equals(ext) || ".txt".equals(ext)) {
            String delimiter = analyzerEntry.getDelimiter();
            int skipRows = analyzerEntry.getSkipRows();
            log.info(
                    "Parsing CSV file {} (delimiter='{}', skipRows={}, perFileTestCode={}, recognitionMode={}) for analyzer {}",
                    filePath.getFileName(), delimiter, skipRows, effectiveFileTestCode,
                    recognition.mode(), analyzerId);
            allResults = FileResultParser.parseCsv(
                    content, columnMappings, delimiter, skipRows,
                    effectiveFileTestCode, recognition,
                    analyzerEntry.getTabularFileLayout(),
                    resultSelection);
        } else if (".xls".equals(ext) || ".xlsx".equals(ext)) {
            log.info(
                    "Parsing Excel file {} (perFileTestCode={}, recognitionMode={}) for analyzer {}",
                    filePath.getFileName(), effectiveFileTestCode,
                    recognition.mode(), analyzerId);
            try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(content)) {
                allResults = FileResultParser.parse(
                        bis, columnMappings, effectiveFileTestCode, recognition,
                        analyzerEntry.getTabularFileLayout(),
                        resultSelection);
            }
        } else if (".ods".equals(ext)) {
            log.info(
                    "Parsing ODS file {} (perFileTestCode={}, recognitionMode={}) for analyzer {}",
                    filePath.getFileName(), effectiveFileTestCode,
                    recognition.mode(), analyzerId);
            try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(content)) {
                allResults = FileResultParser.parseOds(
                        bis, columnMappings, effectiveFileTestCode, recognition,
                        analyzerEntry.getTabularFileLayout(),
                        resultSelection);
            }
        } else {
            throw new FileProcessingException(
                    "Unsupported file extension '" + ext + "' for " + filePath
                            + " — expected one of: .csv, .tsv, .txt, .xls, .xlsx, .ods");
        }

        if (allResults == null || allResults.isEmpty()) {
            throw new FileProcessingException(
                    "FHIR file parse produced no results for " + filePath);
        }

        URI fhirUri = buildFhirUri();

        int totalResults = 0;
        int accessionIndex = 0;
        for (HL7ResultParser.ParsedResults parsed : allResults) {
            accessionIndex++;
            if (progress != null) {
                progress.onAccession(accessionIndex, allResults.size(), parsed.accessionNumber());
            }
            String fhirJson = buildFileFhirBundle(analyzerEntry, parsed, filePath.toString());

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(fhirUri)
                    .timeout(Duration.ofSeconds(httpConfig.getReadTimeoutSeconds()))
                    .header("Content-Type", "application/fhir+json")
                    .POST(HttpRequest.BodyPublishers.ofString(fhirJson));

            addBasicAuth(requestBuilder);

            HttpResponse<String> response;
            try {
                response = httpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FileProcessingException("Interrupted while sending FHIR Bundle", e);
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new FileProcessingException(
                        "OE rejected FHIR Bundle for accession " + parsed.accessionNumber()
                                + ": HTTP " + response.statusCode() + " " + response.body());
            }
            totalResults += parsed.results().size();
        }

        log.info("FHIR file import: {} results across {} accessions from {}",
                totalResults, allResults.size(), filePath.getFileName());
    }

    static String resolveFileTestCode(AnalyzerEntry analyzerEntry, String explicitFileTestCode) {
        if (explicitFileTestCode != null && !explicitFileTestCode.isBlank()) {
            return explicitFileTestCode.trim();
        }
        return analyzerEntry != null ? analyzerEntry.getFileTestCode() : null;
    }

    private URI buildFhirUri() throws FileProcessingException {
        URI baseUri = httpConfig.getUri();
        if (baseUri == null) {
            throw new FileProcessingException("No forward HTTP server URI configured for FILE transport");
        }
        String basePath = baseUri.getPath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/analyzer";
        } else if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        return URI.create(baseUri.getScheme() + "://" + baseUri.getAuthority() + basePath + "/fhir");
    }

    private void addBasicAuth(HttpRequest.Builder requestBuilder) {
        if (httpConfig.getUsername() == null || httpConfig.getUsername().isBlank()) {
            return;
        }

        char[] password = httpConfig.getPassword();
        if (password == null || password.length == 0) {
            log.warn("Forward HTTP password is null or empty, skipping Basic auth");
            return;
        }

        byte[] usernameBytes = httpConfig.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] colonBytes = ":".getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes;
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer byteBuffer = encoder.encode(CharBuffer.wrap(password));
            passwordBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(passwordBytes);
        } catch (Exception e) {
            log.error("Failed to encode forward HTTP password", e);
            return;
        }

        byte[] authBytes = new byte[usernameBytes.length + colonBytes.length + passwordBytes.length];
        System.arraycopy(usernameBytes, 0, authBytes, 0, usernameBytes.length);
        System.arraycopy(colonBytes, 0, authBytes, usernameBytes.length, colonBytes.length);
        System.arraycopy(passwordBytes, 0, authBytes, usernameBytes.length + colonBytes.length,
                passwordBytes.length);

        String token = Base64.getEncoder().encodeToString(authBytes);
        requestBuilder.header("Authorization", "Basic " + token);

        Arrays.fill(passwordBytes, (byte) 0);
        Arrays.fill(authBytes, (byte) 0);
    }

    private static String getFileExtension(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    public static class FileProcessingException extends Exception {
        public FileProcessingException(String message) {
            super(message);
        }

        public FileProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
