package org.itech.ahb.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner.ScanResult;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.file.FileMessageHandler.FileProcessingException;
import org.itech.ahb.file.FileWatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin file-upload endpoint. Validates the admin's declared test code
 * against the analyzer's mapping set and the scanner's self-declaration
 * scan, writes the file to the analyzer's import directory, and invokes
 * {@link FileMessageHandler#processFile(Path, String, String)} directly
 * so FileWatcher doesn't have to re-discover it. Inherits HTTP Basic auth
 * from the existing {@code /admin/**} security rule.
 */
@RestController
@RequestMapping("/admin/upload")
@Slf4j
public class FileUploadController {

    private final AnalyzerRegistryConfig registry;
    private final FileMessageHandler fileMessageHandler;
    private final FileNameSelfDeclarationScanner scanner;
    private final FileWatcher fileWatcher;

    public FileUploadController(AnalyzerRegistryConfig registry,
            FileMessageHandler fileMessageHandler,
            FileNameSelfDeclarationScanner scanner,
            FileWatcher fileWatcher) {
        this.registry = registry;
        this.fileMessageHandler = fileMessageHandler;
        this.scanner = scanner;
        this.fileWatcher = fileWatcher;
    }

    @GetMapping("/analyzers")
    public ResponseEntity<List<Map<String, Object>>> listFileAnalyzers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, AnalyzerEntry> entry : registry.getRegisteredAnalyzers().entrySet()) {
            AnalyzerEntry a = entry.getValue();
            if (!"FILE".equalsIgnoreCase(a.getExpectedProtocol())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName() != null ? a.getName() : a.getId());
            m.put("watchDirectory", entry.getKey());
            m.put("filePattern", a.getFilePattern() != null ? a.getFilePattern() : "*");
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/analyzers/{id}/tests")
    public ResponseEntity<List<String>> listTestCodes(@PathVariable("id") String analyzerId) {
        AnalyzerEntry entry = findEntryById(analyzerId);
        if (entry == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(new ArrayList<>(entry.getMappedTestCodes()));
    }

    /**
     * Multipart upload entry point. Validates analyzer id, test code, file
     * shape, and scanner agreement; writes the file to the analyzer's
     * watch directory; invokes {@link FileMessageHandler#processFile}
     * with the admin's declared test code; returns an HTML banner.
     *
     * <p>Failure modes all return a 4xx with an {@code .banner.error}
     * HTML response that the static form displays inline. Callers that
     * want structured JSON errors should use the {@code /analyzers}
     * endpoints instead.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> uploadFile(
            @RequestParam("analyzerId") String analyzerId,
            @RequestParam(value = "testCode", required = false) String testCode,
            @RequestParam("file") MultipartFile file) {

        AnalyzerEntry entry = findEntryById(analyzerId);
        if (entry == null) {
            return errorHtml(HttpStatus.BAD_REQUEST,
                    "Unknown analyzer id: " + analyzerId);
        }
        if (!"FILE".equalsIgnoreCase(entry.getExpectedProtocol())) {
            return errorHtml(HttpStatus.BAD_REQUEST,
                    "Analyzer " + analyzerId + " is not a FILE analyzer (protocol="
                            + entry.getExpectedProtocol() + ")");
        }

        Set<String> allowedCodes = entry.getMappedTestCodes();
        if (allowedCodes == null || allowedCodes.isEmpty()) {
            return errorHtml(HttpStatus.BAD_REQUEST,
                    "Analyzer " + analyzerId
                            + " has no configured test mappings — refusing upload");
        }
        // testCode is optional — files with per-row test labels (e.g. QuantStudio's
        // Target Name column) don't need a form-level declaration. Only reject if a
        // non-blank value was provided that doesn't match the configured mapping set.
        if (testCode != null && !testCode.isBlank() && !allowedCodes.contains(testCode)) {
            return errorHtml(HttpStatus.BAD_REQUEST,
                    "testCode '" + testCode + "' is not in analyzer's configured mapping set "
                            + allowedCodes);
        }
        // Normalize blank to null so downstream receives a clean signal
        if (testCode != null && testCode.isBlank()) {
            testCode = null;
        }

        if (file == null || file.isEmpty()) {
            return errorHtml(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return errorHtml(HttpStatus.BAD_REQUEST, "Uploaded file has no filename");
        }
        if (originalFilename.contains("/") || originalFilename.contains("\\")
                || originalFilename.contains("..")) {
            return errorHtml(HttpStatus.BAD_REQUEST,
                    "Unsafe filename rejected (path separator or traversal): " + originalFilename);
        }

        String watchDir = null;
        for (Map.Entry<String, AnalyzerEntry> rentry : registry.getRegisteredAnalyzers().entrySet()) {
            if (analyzerId.equals(rentry.getValue().getId())
                    && "FILE".equalsIgnoreCase(rentry.getValue().getExpectedProtocol())) {
                watchDir = rentry.getKey();
                break;
            }
        }
        if (watchDir == null) {
            return errorHtml(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not resolve watch directory for analyzer " + analyzerId);
        }

        Path targetDir = Paths.get(watchDir);
        Path targetFile = targetDir.resolve(originalFilename);

        byte[] fileBytes;
        String contentHash;
        try {
            fileBytes = file.getBytes();
            contentHash = sha256Hex(fileBytes);
        } catch (IOException | NoSuchAlgorithmException e) {
            return errorHtml(HttpStatus.BAD_REQUEST,
                    "Failed to read uploaded bytes: " + e.getMessage());
        }

        // Pre-register the file as PROCESSED so FileWatcher's polling loop
        // sees it as already-handled the moment it appears on disk, avoiding
        // a race where FileWatcher and this controller process the same
        // file concurrently and flood OE with duplicate FHIR bundles.
        try {
            fileWatcher.getStateStore().markProcessed(analyzerId, contentHash, targetFile);
        } catch (RuntimeException e) {
            log.warn("FileUploadController: pre-mark state store failed: {}", e.getMessage());
        }

        try {
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }
            Files.write(targetFile, fileBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("FileUploadController: failed to write {} to {}: {}",
                    originalFilename, targetDir, e.getMessage());
            return errorHtml(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write upload to " + targetDir + ": " + e.getMessage());
        }

        try {
            fileMessageHandler.processFile(targetFile, analyzerId, testCode);
        } catch (FileProcessingException | IOException e) {
            log.warn("FileUploadController: processFile failed for {}: {}",
                    targetFile, e.getMessage());
            return errorHtml(HttpStatus.INTERNAL_SERVER_ERROR,
                    "File upload accepted but processing failed: " + e.getMessage());
        }

        String successBanner = String.format(
                "<div class=\"banner success\">File <code>%s</code> uploaded to <code>%s</code>"
                        + " for analyzer <strong>%s</strong> with test code <strong>%s</strong>.</div>"
                        + "<p><a href=\"/admin/upload/index.html\">Upload another file</a></p>",
                htmlEscape(originalFilename),
                htmlEscape(targetFile.toString()),
                htmlEscape(entry.getName() != null ? entry.getName() : analyzerId),
                htmlEscape(testCode));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(wrapHtml(successBanner));
    }

    /**
     * v5 scanner-as-UX-helper endpoint. Accepts multipart (analyzerId, file),
     * writes file to a temp path, runs the scanner against it, returns JSON
     * with a suggested test code for the upload form's Test dropdown.
     *
     * The scanner's result is purely advisory: the client (admin upload UI)
     * uses the suggestion to pre-select a value in the Test dropdown, and the
     * admin can confirm or override before submitting the actual upload via
     * {@code POST /admin/upload}. The scanner is NOT a gate — it never blocks
     * an upload. Under the v5 simple model, the admin's declared test code is
     * the authoritative source of truth at upload time; the scanner just tries
     * to make the admin's job easier by guessing from file content.
     */
    @PostMapping(value = "/scan",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> scanFile(
            @RequestParam("analyzerId") String analyzerId,
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> response = new LinkedHashMap<>();

        AnalyzerEntry entry = findEntryById(analyzerId);
        if (entry == null) {
            response.put("suggestion", null);
            response.put("confidence", "unknownAnalyzer");
            response.put("reason", "Unknown analyzer id: " + analyzerId);
            return ResponseEntity.ok(response);
        }

        Set<String> allowedCodes = entry.getMappedTestCodes();
        Map<String, String> columnMappings = entry.getColumnMappings();
        if (allowedCodes == null || allowedCodes.isEmpty()
                || columnMappings == null || columnMappings.isEmpty()) {
            response.put("suggestion", null);
            response.put("confidence", "notConfigured");
            response.put("reason", "Analyzer has no column_mapping or mappedTestCodes configured");
            return ResponseEntity.ok(response);
        }

        if (file == null || file.isEmpty()) {
            response.put("suggestion", null);
            response.put("confidence", "emptyFile");
            return ResponseEntity.ok(response);
        }

        Path tempFile = null;
        try {
            String suffix = file.getOriginalFilename() != null
                    && file.getOriginalFilename().contains(".")
                    ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf('.'))
                    : ".bin";
            tempFile = Files.createTempFile("ahb-scan-", suffix);
            Files.write(tempFile, file.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ScanResult scanResult = scanner.scan(tempFile, columnMappings, allowedCodes, getSynonyms(entry));
            if (scanResult instanceof ScanResult.SelfDeclared selfDeclared) {
                response.put("suggestion", selfDeclared.testCode());
                response.put("confidence", "selfDeclared");
            } else if (scanResult instanceof ScanResult.Ambiguous ambiguous) {
                response.put("suggestion", null);
                response.put("confidence", "ambiguous");
                response.put("codes", new ArrayList<>(ambiguous.codes()));
            } else if (scanResult instanceof ScanResult.NotInterpretable notInterpretable) {
                response.put("suggestion", null);
                response.put("confidence", "notInterpretable");
                response.put("reason", notInterpretable.reason());
            } else {
                // NoDeclaration
                response.put("suggestion", null);
                response.put("confidence", "noDeclaration");
            }
        } catch (IOException e) {
            log.warn("FileUploadController: scan failed for {}: {}",
                    file.getOriginalFilename(), e.getMessage());
            response.put("suggestion", null);
            response.put("confidence", "scanError");
            response.put("reason", e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }

        return ResponseEntity.ok(response);
    }

    private AnalyzerEntry findEntryById(String analyzerId) {
        if (analyzerId == null || analyzerId.isBlank()) return null;
        for (AnalyzerEntry e : registry.getRegisteredAnalyzers().values()) {
            if (analyzerId.equals(e.getId())) {
                return e;
            }
        }
        return null;
    }

    private Map<String, List<String>> getSynonyms(AnalyzerEntry entry) {
        Map<String, List<String>> synonyms = entry.getScannerSynonyms();
        return synonyms != null ? synonyms : Collections.emptyMap();
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    private ResponseEntity<String> errorHtml(HttpStatus status, String message) {
        String body = wrapHtml(
                "<div class=\"banner error\">" + htmlEscape(message) + "</div>"
                        + "<p><a href=\"/admin/upload/index.html\">Back to upload</a></p>");
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
    }

    private String wrapHtml(String bodyContent) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>Analyzer File Upload — OpenELIS Bridge Admin</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:2rem auto;padding:0 1rem;color:#222}"
                + ".banner{padding:0.75rem;border-radius:4px;margin-bottom:1rem}"
                + ".banner.success{background:#d4edda;border:1px solid #c3e6cb}"
                + ".banner.error{background:#f8d7da;border:1px solid #f5c6cb}"
                + "code{background:#eee;padding:0 0.2rem}</style></head><body>"
                + "<h1>Analyzer File Upload — Bridge Admin</h1>"
                + bodyContent
                + "</body></html>";
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

}
