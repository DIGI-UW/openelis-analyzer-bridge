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
import lombok.extern.slf4j.Slf4j;
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

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

    @Autowired
    public FileMessageHandler(CSVParser csvParser) {
        this.csvParser = csvParser;
    }

    public FileMessageHandler(CSVParser csvParser, FileConfig ignoredFileConfig,
            org.itech.ahb.normalizer.MessageNormalizer ignoredNormalizer) {
        this.csvParser = csvParser;
    }

    private HttpClient httpClient() {
        HttpClient existing = httpClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                        .build();
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

        postFileToOpenElis(filePath, analyzerId, content);

        return MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId(filePath.toString())
                .rawMessage(filePath.getFileName().toString())
                .analyzerId(analyzerId)
                .build();
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
