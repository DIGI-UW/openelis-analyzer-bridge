package org.itech.ahb.file;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
@Component("analyzerFileWatcher")
@Slf4j
public class FileWatcher {

    private final FileConfig fileConfig;
    private final FileMessageHandler messageHandler;

    private final Map<Path, FileMetadata> fileStabilityTracker = new ConcurrentHashMap<>();
    private final Set<String> processedFileHashes = ConcurrentHashMap.newKeySet();
    private final Map<Path, RetryInfo> retryTracker = new ConcurrentHashMap<>();
    private final Map<Path, WatchKey> watchedDirectories = new ConcurrentHashMap<>();
    private final Map<Path, String> directoryAnalyzerMap = new ConcurrentHashMap<>();

    // Maximum number of file hashes to keep in memory (prevent unbounded growth)
    private static final int MAX_HASH_CACHE_SIZE = 10000;

    private WatchService watchService;
    private ExecutorService watcherExecutor;
    private ExecutorService processorExecutor;
    private final ScheduledExecutorService stabilityChecker = Executors.newScheduledThreadPool(1);

    private volatile boolean running = false;

    public boolean isRunning() {
        return running;
    }

    public FileWatcher(FileConfig fileConfig, FileMessageHandler messageHandler) {
        this.fileConfig = fileConfig;
        this.messageHandler = messageHandler;
    }

    /**
     * Start file watcher service.
     */
    @PostConstruct
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        log.info("Starting file watcher service...");

        // Create archive and error directories if they don't exist
        Files.createDirectories(Paths.get(fileConfig.getArchiveDirectory()));
        Files.createDirectories(Paths.get(fileConfig.getErrorDirectory()));

        // Initialize watch service and executors
        watchService = FileSystems.getDefault().newWatchService();
        watcherExecutor = Executors.newSingleThreadExecutor();
        processorExecutor = Executors.newFixedThreadPool(2);

        // Register bootstrap directories (if any)
        for (String watchDir : fileConfig.getWatchDirectories()) {
            if (watchDir == null || watchDir.isBlank()) {
                continue;
            }
            registerDirectoryInternal(Paths.get(watchDir).normalize(), null, true);
        }

        running = true;
        watcherExecutor.submit(this::watchLoop);

        // Start stability checker (runs periodically)
        stabilityChecker.scheduleWithFixedDelay(
                this::checkStableFiles,
                fileConfig.getFileStabilityTimeoutMs(),
                fileConfig.getPollIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        log.info("File watcher service started successfully with {} watched directories", watchedDirectories.size());
    }

    /**
     * Register a directory for runtime watching.
     */
    public synchronized void addWatchDirectory(Path dirPath, String filePattern, String analyzerId) throws IOException {
        Path normalized = dirPath.normalize();
        registerDirectoryInternal(normalized, analyzerId, true);
        if (!fileConfig.getWatchDirectories().contains(normalized.toString())) {
            List<String> mutableWatchDirs = new ArrayList<>(fileConfig.getWatchDirectories());
            mutableWatchDirs.add(normalized.toString());
            fileConfig.setWatchDirectories(mutableWatchDirs);
        }
        log.info("Runtime watch directory registered: {} (analyzerId={})", normalized, analyzerId);
    }

    /**
     * Remove a directory from runtime watching.
     */
    public synchronized boolean removeWatchDirectory(Path dirPath) {
        Path normalized = dirPath.normalize();
        WatchKey key = watchedDirectories.remove(normalized);
        directoryAnalyzerMap.remove(normalized);
        List<String> mutableWatchDirs = new ArrayList<>(fileConfig.getWatchDirectories());
        mutableWatchDirs.remove(normalized.toString());
        fileConfig.setWatchDirectories(mutableWatchDirs);
        if (key != null) {
            key.cancel();
            log.info("Runtime watch directory removed: {}", normalized);
            return true;
        }
        return false;
    }

    /**
     * Remove all watched directories for a given analyzer ID.
     */
    public synchronized int removeWatchDirectoriesByAnalyzerId(String analyzerId) {
        int removed = 0;
        for (Map.Entry<Path, String> entry : new ArrayList<>(directoryAnalyzerMap.entrySet())) {
            if (Objects.equals(entry.getValue(), analyzerId) && removeWatchDirectory(entry.getKey())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Register a directory with WatchService and optionally process existing files.
     */
    private void registerDirectoryInternal(Path dirPath, String analyzerId, boolean processExisting) throws IOException {
        if (!Files.exists(dirPath)) {
            log.warn("Watch directory does not exist, creating: {}", dirPath);
            Files.createDirectories(dirPath);
        }

        WatchKey oldKey = watchedDirectories.remove(dirPath);
        if (oldKey != null) {
            oldKey.cancel();
        }

        WatchKey key = dirPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        watchedDirectories.put(dirPath, key);
        if (analyzerId != null && !analyzerId.isBlank()) {
            directoryAnalyzerMap.put(dirPath, analyzerId);
        }

        log.info("Registered watch directory: {}", dirPath);
        if (processExisting && processorExecutor != null) {
            processExistingFiles(dirPath);
        }
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
            try {
                // Always read fresh file attributes to avoid race conditions
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                Instant currentLastModified = attrs.lastModifiedTime().toInstant();
                long currentSize = attrs.size();

                long timeSinceModification = now.toEpochMilli() - currentLastModified.toEpochMilli();

                if (timeSinceModification >= fileConfig.getFileStabilityTimeoutMs()) {
                    // File is stable - both timestamp and size match metadata snapshot
                    if (currentSize == metadata.size && currentLastModified.equals(metadata.lastModified)) {
                        stableFiles.add(path);
                    } else {
                        // File changed since last check, update metadata
                        fileStabilityTracker.put(path, new FileMetadata(currentLastModified, currentSize));
                        log.debug("File still changing: {}, updated metadata", path.getFileName());
                    }
                } else {
                    // Not yet stable, refresh metadata to track latest state
                    if (!currentLastModified.equals(metadata.lastModified) || currentSize != metadata.size) {
                        fileStabilityTracker.put(path, new FileMetadata(currentLastModified, currentSize));
                        log.debug("File modified during stability check: {}", path.getFileName());
                    }
                }
            } catch (IOException e) {
                log.warn("File disappeared before processing: {}", path);
                fileStabilityTracker.remove(path);
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
     * Archive successfully processed file with path traversal protection.
     * <p>
     * If archiving fails, moves file to error directory to prevent reprocessing.
     * </p>
     */
    private void archiveFile(Path filePath, String subdirectory) {
        try {
            Path baseArchiveDir = Paths.get(fileConfig.getArchiveDirectory()).normalize();
            Path archiveDir = baseArchiveDir;

            if (subdirectory != null) {
                archiveDir = baseArchiveDir.resolve(subdirectory).normalize();
                // Validate subdirectory doesn't escape base archive directory
                if (!archiveDir.startsWith(baseArchiveDir)) {
                    log.error("Invalid subdirectory (path traversal): {}", subdirectory);
                    archiveDir = baseArchiveDir;
                }
            }

            Files.createDirectories(archiveDir);

            Path targetPath = archiveDir.resolve(filePath.getFileName()).normalize();

            // Validate final path stays within base archive directory (defense-in-depth)
            if (!targetPath.startsWith(baseArchiveDir)) {
                log.error("Path traversal detected in archive operation: {}", targetPath);
                moveToErrorDirectory(filePath, new SecurityException("Path traversal detected"));
                return;
            }

            // Handle name collision
            int counter = 1;
            while (Files.exists(targetPath)) {
                String filename = filePath.getFileName().toString();
                int dotIndex = filename.lastIndexOf('.');
                String name = (dotIndex > 0) ? filename.substring(0, dotIndex) : filename;
                String ext = (dotIndex > 0) ? filename.substring(dotIndex) : "";
                targetPath = archiveDir.resolve(name + "_" + counter + ext).normalize();
                counter++;

                if (counter > 1000) {
                    log.error("Too many archive collisions for file: {}", filePath.getFileName());
                    break;
                }
            }

            Files.move(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Archived file to: {}", targetPath);

        } catch (IOException e) {
            log.error("Failed to archive file: {}, moving to error directory", filePath, e);
            moveToErrorDirectory(filePath, e);
        }
    }

    /**
     * Move failed file to error directory with path traversal protection.
     * <p>
     * If moving to error directory fails, uses fail-safe mechanism to mark file
     * as permanently failed in the watch directory to prevent infinite retries.
     * </p>
     */
    private void moveToErrorDirectory(Path filePath, Exception error) {
        try {
            Path errorDir = Paths.get(fileConfig.getErrorDirectory()).normalize();
            Files.createDirectories(errorDir);

            Path targetPath = errorDir.resolve(filePath.getFileName()).normalize();

            // Validate path stays within error directory (prevent traversal attacks)
            if (!targetPath.startsWith(errorDir)) {
                log.error("Path traversal detected in error directory operation: {}", targetPath);
                markAsFailedInPlace(filePath, error);
                return;
            }

            // Write error details to accompanying .error file
            Path errorDetailsPath = errorDir.resolve(filePath.getFileName() + ".error").normalize();
            Files.writeString(errorDetailsPath, "Error: " + error.getMessage() + "\n" +
                    "Timestamp: " + Instant.now() + "\n" +
                    "OriginalPath: " + filePath + "\n");

            Files.move(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Moved failed file to error directory: {}", targetPath);

        } catch (IOException e) {
            log.error("Failed to move file to error directory: {}, using fail-safe", filePath, e);
            markAsFailedInPlace(filePath, error);
        }
    }

    /**
     * Fail-safe: Mark file as permanently failed in watch directory.
     * <p>
     * Used when moving to error directory fails. Prevents infinite retry loops.
     * </p>
     */
    private void markAsFailedInPlace(Path filePath, Exception error) {
        try {
            String failedFileName = filePath.getFileName().toString() + ".failed";
            Path failedPath = filePath.resolveSibling(failedFileName);

            // Write error details
            Files.writeString(failedPath, "FAILED PROCESSING\n" +
                    "Error: " + error.getMessage() + "\n" +
                    "Timestamp: " + Instant.now() + "\n" +
                    "OriginalFile: " + filePath.getFileName() + "\n");

            // Try to delete original file
            Files.deleteIfExists(filePath);
            log.warn("Marked file as permanently failed in watch directory: {}", failedPath);

        } catch (IOException ex) {
            log.error("Failed to mark file as permanently failed: {}, manual intervention required", filePath, ex);
        }
    }

    /**
     * Calculate SHA-256 hash of file content using streaming to handle large files.
     * <p>
     * SHA-256 is guaranteed to be available in all Java implementations,
     * so NoSuchAlgorithmException should never occur. If it does, it indicates
     * a broken JVM and the watcher should fail fast.
     * </p>
     */
    private String calculateFileHash(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Stream file in chunks to avoid loading entire file into memory
            try (InputStream fis = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hashBytes = digest.digest();

            // Convert to hex string
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            // This should never happen for SHA-256 (part of Java spec)
            // If it does, it's a fatal JVM error
            log.error("FATAL: SHA-256 algorithm not available - broken JVM", e);
            throw new IllegalStateException("SHA-256 not available - JVM is broken", e);
        }
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

        Path parent = filePath.getParent();
        if (parent != null) {
            String mappedAnalyzerId = directoryAnalyzerMap.get(parent.normalize());
            if (mappedAnalyzerId != null && !mappedAnalyzerId.isBlank()) {
                return mappedAnalyzerId;
            }
        }

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

        // Skip hidden files, error detail files, and permanently failed files
        if (filename.startsWith(".") || filename.endsWith(".error") || filename.endsWith(".failed")) {
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
