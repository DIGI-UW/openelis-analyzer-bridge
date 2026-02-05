package org.itech.ahb.file;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * File system watcher for analyzer result files.
 * <p>
 * Monitors configured directories for new files matching specified patterns,
 * implements file stability checking, duplicate detection (hash-based), and
 * retry logic with exponential backoff.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")
@Slf4j
public class FileWatcher {

    private final FileConfig fileConfig;
    private final FileMessageHandler messageHandler;

    private final Map<Path, FileMetadata> fileStabilityTracker = new ConcurrentHashMap<>();
    private final Set<String> processedFileHashes = ConcurrentHashMap.newKeySet();
    private final Map<Path, RetryInfo> retryTracker = new ConcurrentHashMap<>();

    // Maximum number of file hashes to keep in memory (prevent unbounded growth)
    private static final int MAX_HASH_CACHE_SIZE = 10000;

    private WatchService watchService;
    private ExecutorService watcherExecutor;
    private ExecutorService processorExecutor;
    private final ScheduledExecutorService stabilityChecker = Executors.newScheduledThreadPool(1);

    private volatile boolean running = false;

    public FileWatcher(FileConfig fileConfig, FileMessageHandler messageHandler) {
        this.fileConfig = fileConfig;
        this.messageHandler = messageHandler;
    }

    /**
     * Start file watcher service.
     */
    @PostConstruct
    public void start() throws IOException {
        if (!fileConfig.isEnabled()) {
            log.info("File watcher is disabled");
            return;
        }

        log.info("Starting file watcher service...");

        // Create archive and error directories if they don't exist
        Files.createDirectories(Paths.get(fileConfig.getArchiveDirectory()));
        Files.createDirectories(Paths.get(fileConfig.getErrorDirectory()));

        // Initialize watch service
        watchService = FileSystems.getDefault().newWatchService();

        // Register watch directories
        for (String watchDir : fileConfig.getWatchDirectories()) {
            Path dirPath = Paths.get(watchDir);
            if (!Files.exists(dirPath)) {
                log.warn("Watch directory does not exist, creating: {}", watchDir);
                Files.createDirectories(dirPath);
            }

            dirPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            log.info("Registered watch directory: {}", dirPath);

            // Process existing files in directory
            processExistingFiles(dirPath);
        }

        // Start watcher thread
        watcherExecutor = Executors.newSingleThreadExecutor();
        processorExecutor = Executors.newFixedThreadPool(2);

        running = true;
        watcherExecutor.submit(this::watchLoop);

        // Start stability checker (runs periodically)
        stabilityChecker.scheduleWithFixedDelay(
                this::checkStableFiles,
                fileConfig.getFileStabilityTimeoutMs(),
                fileConfig.getPollIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        log.info("File watcher service started successfully");
    }

    /**
     * Stop file watcher service with graceful shutdown.
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping file watcher service...");
        running = false;

        // Shutdown executors gracefully
        shutdownExecutor(watcherExecutor, "watcher");
        shutdownExecutor(processorExecutor, "processor");
        shutdownExecutor(stabilityChecker, "stability-checker");

        // Close watch service
        closeWatchService();

        log.info("File watcher service stopped");
    }

    /**
     * Shutdown executor service gracefully with timeout.
     *
     * @param executor the executor to shutdown
     * @param name     executor name for logging
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate gracefully within 30s, forcing shutdown", name);
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("{} executor did not terminate after forced shutdown", name);
                }
            } else {
                log.debug("{} executor shutdown successfully", name);
            }
        } catch (InterruptedException e) {
            log.warn("{} executor shutdown interrupted", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Close watch service with error handling.
     */
    private void closeWatchService() {
        try {
            if (watchService != null) {
                watchService.close();
                log.debug("Watch service closed successfully");
            }
        } catch (IOException e) {
            log.error("Error closing watch service", e);
        }
    }

    /**
     * Watch loop - monitors for file system events.
     */
    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(fileConfig.getPollIntervalMs(), TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        log.warn("Watch service overflow - some events may have been lost");
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path filename = pathEvent.context();
                    Path directory = (Path) key.watchable();
                    Path fullPath = directory.resolve(filename);

                    if (shouldProcessFile(fullPath)) {
                        onFileEvent(fullPath, kind);
                    }
                }

                key.reset();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Watch loop interrupted");
                break;
            } catch (Exception e) {
                log.error("Error in watch loop", e);
            }
        }
    }

    /**
     * Handle file event (create or modify).
     */
    private void onFileEvent(Path filePath, WatchEvent.Kind<?> kind) {
        log.debug("File event: {} for file: {}", kind.name(), filePath.getFileName());

        // Track file for stability checking
        if (!Files.isDirectory(filePath)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                fileStabilityTracker.put(filePath, new FileMetadata(
                        attrs.lastModifiedTime().toInstant(),
                        attrs.size()
                ));
            } catch (IOException e) {
                log.warn("Failed to read file attributes for: {}", filePath, e);
            }
        }
    }

    /**
     * Check for stable files and process them.
     * Also performs periodic cleanup of hash cache to prevent memory leaks.
     */
    private void checkStableFiles() {
        // Periodic cleanup of hash cache to prevent unbounded growth
        if (processedFileHashes.size() > MAX_HASH_CACHE_SIZE) {
            log.warn("Processed file hash cache exceeded {} entries, clearing cache", MAX_HASH_CACHE_SIZE);
            processedFileHashes.clear();
        }

        List<Path> stableFiles = new ArrayList<>();
        Instant now = Instant.now();

        // Find files that haven't been modified for stability timeout period
        fileStabilityTracker.forEach((path, metadata) -> {
            long timeSinceModification = now.toEpochMilli() - metadata.lastModified.toEpochMilli();

            if (timeSinceModification >= fileConfig.getFileStabilityTimeoutMs()) {
                // Double-check file size hasn't changed
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    if (attrs.size() == metadata.size) {
                        stableFiles.add(path);
                    } else {
                        // Size changed, update metadata
                        fileStabilityTracker.put(path, new FileMetadata(
                                attrs.lastModifiedTime().toInstant(),
                                attrs.size()
                        ));
                    }
                } catch (IOException e) {
                    log.warn("File disappeared before processing: {}", path);
                    fileStabilityTracker.remove(path);
                }
            }
        });

        // Process stable files
        for (Path stablePath : stableFiles) {
            fileStabilityTracker.remove(stablePath);
            processorExecutor.submit(() -> processFileWithRetry(stablePath));
        }
    }

    /**
     * Process existing files in directory on startup.
     */
    private void processExistingFiles(Path directory) {
        log.info("Processing existing files in: {}", directory);

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(this::shouldProcessFile)
                    .forEach(file -> {
                        log.info("Found existing file: {}", file.getFileName());
                        processorExecutor.submit(() -> processFileWithRetry(file));
                    });
        } catch (IOException e) {
            log.error("Failed to list existing files in: {}", directory, e);
        }
    }

    /**
     * Process file with retry logic.
     */
    private void processFileWithRetry(Path filePath) {
        try {
            // Check for duplicate
            String fileHash = calculateFileHash(filePath);
            if (processedFileHashes.contains(fileHash)) {
                log.info("Skipping duplicate file (hash match): {}", filePath.getFileName());
                archiveFile(filePath, "duplicate");
                return;
            }

            // Determine analyzer ID from file path/pattern
            String analyzerId = determineAnalyzerId(filePath);

            // Process file
            log.info("Processing file: {} for analyzer: {}", filePath.getFileName(), analyzerId);
            messageHandler.processFile(filePath, analyzerId);

            // Success - archive file and remember hash
            processedFileHashes.add(fileHash);
            archiveFile(filePath, null);
            retryTracker.remove(filePath);

            log.info("Successfully processed file: {}", filePath.getFileName());

        } catch (Exception e) {
            handleProcessingFailure(filePath, e);
        }
    }

    /**
     * Handle processing failure with retry logic.
     */
    private void handleProcessingFailure(Path filePath, Exception error) {
        RetryInfo retryInfo = retryTracker.computeIfAbsent(filePath, k -> new RetryInfo());

        retryInfo.attemptCount++;
        log.warn("File processing failed (attempt {}/{}): {} - {}",
                retryInfo.attemptCount, fileConfig.getMaxRetryAttempts(),
                filePath.getFileName(), error.getMessage());

        if (retryInfo.attemptCount >= fileConfig.getMaxRetryAttempts()) {
            // Max retries exceeded - move to error directory
            log.error("Max retries exceeded for file: {}, moving to error directory", filePath.getFileName());
            moveToErrorDirectory(filePath, error);
            retryTracker.remove(filePath);
        } else {
            // Schedule retry with exponential backoff
            long delay = fileConfig.getRetryDelayMs() * (long) Math.pow(2, retryInfo.attemptCount - 1);
            log.info("Scheduling retry for file: {} in {}ms", filePath.getFileName(), delay);

            stabilityChecker.schedule(() -> processFileWithRetry(filePath), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Archive successfully processed file.
     */
    private void archiveFile(Path filePath, String subdirectory) {
        try {
            Path archiveDir = Paths.get(fileConfig.getArchiveDirectory());
            if (subdirectory != null) {
                archiveDir = archiveDir.resolve(subdirectory);
            }

            Files.createDirectories(archiveDir);

            Path targetPath = archiveDir.resolve(filePath.getFileName());

            // Handle name collision
            int counter = 1;
            while (Files.exists(targetPath)) {
                String filename = filePath.getFileName().toString();
                int dotIndex = filename.lastIndexOf('.');
                String name = (dotIndex > 0) ? filename.substring(0, dotIndex) : filename;
                String ext = (dotIndex > 0) ? filename.substring(dotIndex) : "";
                targetPath = archiveDir.resolve(name + "_" + counter + ext);
                counter++;
            }

            Files.move(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Archived file to: {}", targetPath);

        } catch (IOException e) {
            log.error("Failed to archive file: {}", filePath, e);
        }
    }

    /**
     * Move failed file to error directory.
     */
    private void moveToErrorDirectory(Path filePath, Exception error) {
        try {
            Path errorDir = Paths.get(fileConfig.getErrorDirectory());
            Files.createDirectories(errorDir);

            Path targetPath = errorDir.resolve(filePath.getFileName());

            // Write error details to accompanying .error file
            Path errorDetailsPath = Paths.get(targetPath + ".error");
            Files.writeString(errorDetailsPath, "Error: " + error.getMessage() + "\n" +
                    "Timestamp: " + Instant.now() + "\n");

            Files.move(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Moved failed file to error directory: {}", targetPath);

        } catch (IOException e) {
            log.error("Failed to move file to error directory: {}", filePath, e);
        }
    }

    /**
     * Calculate SHA-256 hash of file content.
     */
    private String calculateFileHash(Path filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(filePath);
        byte[] hashBytes = digest.digest(fileBytes);

        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Determine analyzer ID from file path pattern.
     * <p>
     * Matches file path against configured analyzer patterns.
     * Falls back to parent directory name if no patterns match.
     * </p>
     *
     * @param filePath the file path to analyze
     * @return analyzer ID or null if cannot be determined
     */
    private String determineAnalyzerId(Path filePath) {
        String pathString = filePath.toString();
        String filename = filePath.getFileName().toString();

        // Check configured analyzer patterns
        for (Map.Entry<String, FileConfig.AnalyzerConfig> entry : fileConfig.getAnalyzers().entrySet()) {
            String pattern = entry.getKey();
            FileConfig.AnalyzerConfig config = entry.getValue();

            // Use filePattern if specified, otherwise use key as pattern
            String matchPattern = config.getFilePattern() != null ? config.getFilePattern() : pattern;

            // Glob pattern matching (e.g., "quantstudio-*")
            if (matchPattern.contains("*") || matchPattern.contains("?")) {
                try {
                    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + matchPattern);
                    if (matcher.matches(filePath.getFileName())) {
                        log.debug("Matched file {} to analyzer {} via glob pattern: {}",
                                filename, config.getId(), matchPattern);
                        return config.getId();
                    }
                } catch (Exception e) {
                    log.warn("Invalid glob pattern: {}", matchPattern, e);
                }
            }
            // Substring matching (e.g., "quantstudio")
            else if (pathString.contains(matchPattern) || filename.contains(matchPattern)) {
                log.debug("Matched file {} to analyzer {} via substring: {}",
                        filename, config.getId(), matchPattern);
                return config.getId();
            }
        }

        // Fallback: use parent directory name
        Path parent = filePath.getParent();
        if (parent != null) {
            String dirName = parent.getFileName().toString().toUpperCase();
            log.debug("No pattern match for file {}, using directory name: {}", filename, dirName);
            return dirName;
        }

        log.debug("Could not determine analyzer ID for file: {}", filePath);
        return null;
    }

    /**
     * Check if file should be processed based on configured patterns.
     */
    private boolean shouldProcessFile(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return false;
        }

        String filename = filePath.getFileName().toString();

        // Skip hidden files and error detail files
        if (filename.startsWith(".") || filename.endsWith(".error")) {
            return false;
        }

        // Check against configured patterns
        for (String pattern : fileConfig.getFilePatterns()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            if (matcher.matches(filePath.getFileName())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Metadata for tracking file stability.
     */
    private record FileMetadata(Instant lastModified, long size) {
    }

    /**
     * Retry tracking information.
     */
    private static class RetryInfo {
        int attemptCount = 0;
    }
}
