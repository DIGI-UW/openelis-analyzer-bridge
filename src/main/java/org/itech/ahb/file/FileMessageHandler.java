package org.itech.ahb.file;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.config.FhirRoutingConfig;
import org.itech.ahb.fhir.FileResultParser;
import org.itech.ahb.fhir.FhirBundleBuilder;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles file-based analyzer messages.
 *
 * For FILE transport, the bridge sends files directly to OpenELIS direct-import
 * endpoint (`/rest/analyzers/{id}/import`) instead of routing through the
 * message normalizer and legacy `/analyzer/csv` path.
 */
@Component
@Slf4j
public class FileMessageHandler {

    private final CSVParser csvParser;
    private final FhirRoutingConfig fhirConfig;
    private final AnalyzerRegistryConfig registry;

    private volatile HttpClient httpClient;

    /** Defaults support manual construction in tests; {@code @Value} overrides when Spring creates the bean. */
    @Value("${bridge.openelis.url:http://localhost:8443}")
    private String openelisBaseUrl = "http://localhost:8443";

    @Value("${bridge.openelis.username:}")
    private String openelisUsername = "";

    @Value("${bridge.openelis.password:}")
    private String openelisPassword = "";

    @Value("${bridge.openelis.connectTimeoutSeconds:30}")
    private int connectTimeoutSeconds = 30;

    @Value("${bridge.openelis.readTimeoutSeconds:30}")
    private int readTimeoutSeconds = 30;

    @Value("${bridge.openelis.insecureTls:false}")
    private boolean insecureTls = false;

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

    @Autowired
    public FileMessageHandler(CSVParser csvParser,
            @Autowired(required = false) FhirRoutingConfig fhirConfig,
            @Autowired(required = false) AnalyzerRegistryConfig registry) {
        this.csvParser = csvParser;
        this.fhirConfig = fhirConfig;
        this.registry = registry;
    }

    public FileMessageHandler(CSVParser csvParser, FileConfig ignoredFileConfig,
            org.itech.ahb.normalizer.MessageNormalizer ignoredNormalizer) {
        this.csvParser = csvParser;
        this.fhirConfig = null;
        this.registry = null;
    }

    private HttpClient httpClient() {
        HttpClient existing = httpClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = org.itech.ahb.util.HttpClientFactory.create(
                        connectTimeoutSeconds, insecureTls, "file-import");
            }
            return httpClient;
        }
    }

    public MessageEnvelope processFile(Path filePath, String analyzerId) throws IOException, FileProcessingException {
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

        // FHIR routing: parse file → FHIR Bundle → POST /analyzer/fhir
        if (fhirConfig != null && fhirConfig.isUseFhir()) {
            postFileAsFhir(filePath, analyzerId, content);
        } else {
            postFileToOpenElis(filePath, analyzerId, content);
        }

        return MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId(filePath.toString())
                .rawMessage(filePath.getFileName().toString())
                .resolvedAnalyzerId(analyzerId)
                .analyzerId(analyzerId)
                .build();
    }

    private void postFileAsFhir(Path filePath, String analyzerId, byte[] content)
            throws IOException, FileProcessingException {
        // Get column mappings from registry
        Map<String, String> columnMappings = null;
        if (registry != null) {
            for (Map.Entry<String, AnalyzerEntry> entry : registry.getRegisteredAnalyzers().entrySet()) {
                if (analyzerId.equals(entry.getValue().getId())) {
                    columnMappings = entry.getValue().getColumnMappings();
                    break;
                }
            }
        }

        if (columnMappings == null || columnMappings.isEmpty()) {
            log.warn("No column mappings for analyzer {} — falling back to legacy file import", analyzerId);
            postFileToOpenElis(filePath, analyzerId, content);
            return;
        }

        // Parse file using column mappings
        List<HL7ResultParser.ParsedResults> allResults;
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(content)) {
            allResults = FileResultParser.parse(bis, columnMappings);
        }

        if (allResults == null || allResults.isEmpty()) {
            log.warn("FHIR file parse produced no results for {} — falling back to legacy", filePath);
            postFileToOpenElis(filePath, analyzerId, content);
            return;
        }

        // Build and send FHIR Bundle for each accession group
        String base = openelisBaseUrl.endsWith("/") ? openelisBaseUrl.substring(0, openelisBaseUrl.length() - 1)
                : openelisBaseUrl;
        URI fhirUri = URI.create(base + "/analyzer/fhir");

        int totalResults = 0;
        for (HL7ResultParser.ParsedResults parsed : allResults) {
            String fhirJson = FhirBundleBuilder.buildBundle(
                    parsed.accessionNumber(), analyzerId, parsed.results());

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(fhirUri)
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
                    .header("Content-Type", "application/fhir+json")
                    .header("X-Analyzer-Id", analyzerId)
                    .POST(HttpRequest.BodyPublishers.ofString(fhirJson));

            if (openelisUsername != null && !openelisUsername.isBlank()) {
                String token = Base64.getEncoder()
                        .encodeToString((openelisUsername + ":" + (openelisPassword == null ? "" : openelisPassword))
                                .getBytes(StandardCharsets.UTF_8));
                requestBuilder.header("Authorization", "Basic " + token);
            }

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

    private void postFileToOpenElis(Path filePath, String analyzerId, byte[] content)
            throws IOException, FileProcessingException {
        String boundary = "----OpenElisBridgeBoundary" + System.currentTimeMillis();
        String safeFilename = sanitizeMultipartFilename(filePath.getFileName().toString());

        byte[] preamble = (
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8);

        byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[preamble.length + content.length + closing.length];
        System.arraycopy(preamble, 0, body, 0, preamble.length);
        System.arraycopy(content, 0, body, preamble.length, content.length);
        System.arraycopy(closing, 0, body, preamble.length + content.length, closing.length);

        String base = openelisBaseUrl.endsWith("/") ? openelisBaseUrl.substring(0, openelisBaseUrl.length() - 1)
                : openelisBaseUrl;
        URI uri = URI.create(base + "/rest/analyzers/" + analyzerId + "/import");

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(readTimeoutSeconds))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Analyzer-Id", analyzerId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (openelisUsername != null && !openelisUsername.isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((openelisUsername + ":" + (openelisPassword == null ? "" : openelisPassword))
                            .getBytes(StandardCharsets.UTF_8));
            requestBuilder.header("Authorization", "Basic " + token);
        }

        HttpResponse<String> response;
        try {
            response = httpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileProcessingException("Interrupted while forwarding file to OpenELIS", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new FileProcessingException(
                    "OpenELIS direct import failed for analyzer " + analyzerId +
                            " with status " + response.statusCode() + ": " + response.body());
        }

        log.info("Forwarded file {} to OpenELIS direct-import endpoint for analyzer {}", safeFilename, analyzerId);
    }

    private static String sanitizeMultipartFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.bin";
        }
        return filename.replace("\r", "").replace("\n", "").replace("\"", "");
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
