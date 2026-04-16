package org.itech.ahb.file;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
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
 * File system poller for analyzer result files.
 * <p>
 * Uses Apache Commons IO {@link FileAlterationMonitor} for reliable polling-based
 * file detection. This replaces the previous Java NIO {@code WatchService} implementation
 * which was unreliable in Docker bind-mount environments (macOS, Windows) and on
 * network filesystems (NFS, CIFS, Kubernetes configmaps).
 * </p>
 * <p>
 * Detection flow: polling discovers new/changed files → stability checker waits
 * for file to stop changing → processor forwards to OpenELIS via HTTP → results
 * are recorded in {@link FileStateStore} as metadata. <b>The bridge never deletes
 * or moves files from the watched directory.</b> Operators clean up their own
 * source directories; the bridge's job is strictly to observe and record.
 * </p>
 * <p>
 * State store transitions: new observation → RETRYING → PROCESSED (success) or
 * FAILED_NEEDS_HANDLING (max retries exhausted). The latter is a terminal state
 * with no further automatic retries — the file remains in place for human
 * inspection and a future notifier hook reads from the state store.
 * </p>
 */
@Component("analyzerFileWatcher")
@Slf4j
public class FileWatcher {

    private final FileConfig fileConfig;
    private final FileMessageHandler messageHandler;

    private final Map<Path, FileMetadata> fileStabilityTracker = new ConcurrentHashMap<>();
    /**
     * Registrations for each watched directory. A single directory may host
     * multiple analyzer registrations, each with its own glob pattern and its
     * own {@link FileAlterationObserver}. This is the refactor that unblocks
     * Madagascar's Fluorocycler XT workflow where one physical folder hosts
     * both HIV VL ({@code HIV*.xlsx}) and Arbovirus ({@code ARBO*.xlsx}) runs
     * under different analyzer instances. Apache Commons IO's
     * {@link FileAlterationMonitor} supports multiple observers per directory
     * natively (internal {@code CopyOnWriteArrayList}); the prior single-
     * entry-per-path maps were an artificial constraint.
     * <p>
     * Access is serialized through {@code synchronized} methods on the outer
     * class; the map stores {@link CopyOnWriteArrayList} so iteration during
     * processing is safe even when registrations mutate.
     */
    private final Map<Path, List<WatchRegistration>> registrationsByDirectory = new ConcurrentHashMap<>();
    /** Directories that failed creation during registration — retried by rescan. */
    private final Set<Path> pendingDirectories = ConcurrentHashMap.newKeySet();

    /**
     * A single analyzer's watch registration for a given directory. Holds the
     * analyzer id, its configured glob pattern (used by {@link #shouldProcessFile}
     * and {@link #determineAnalyzerId}), and the Commons IO observer that feeds
     * events into our listener. Multiple registrations may share the same
     * directory path; each has its own observer so file events propagate to
     * all relevant analyzers.
     */
    private record WatchRegistration(String analyzerId, String globPattern, FileAlterationObserver observer) {
    }
    /**
     * In-process lock: prevents two threads from processing the same path
     * concurrently within one JVM. Not persistent — a crash clears it and the
     * state store's RETRYING/next_attempt_at fields carry the scheduling forward.
     */
    private final Set<Path> processingFiles = ConcurrentHashMap.newKeySet();

    private FileAlterationMonitor monitor;
    private ExecutorService processorExecutor;
    private final ScheduledExecutorService stabilityChecker = Executors.newScheduledThreadPool(1);
    /**
     * Durable per-file processing state. Replaces the former in-memory
     * {@code processedFileHashes} / {@code retryTracker} / {@code permanentlySkippedFiles}
     * sets. Initialized in {@link #start()}; closed in {@link #stop()}.
     */
    private FileStateStore stateStore;

    private volatile boolean running = false;

    public boolean isRunning() {
        return running;
    }

    public FileWatcher(FileConfig fileConfig, FileMessageHandler messageHandler,
                       @Autowired(required = false) SqliteFileStateStore stateStore) {
        this.fileConfig = fileConfig;
        this.messageHandler = messageHandler;
        // May be null when bridge.file.enabled=false (StateStoreConfig is
        // @ConditionalOnProperty and does not register the bean in that case).
        // start() bails out on !fileConfig.isEnabled() before touching the
        // store, so null here is safe.
        this.stateStore = stateStore;
    }

    /**
     * Start file watcher service using polling-based detection.
     */
    @PostConstruct
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        if (!fileConfig.isEnabled()) {
            log.info("File watcher bootstrap skipped (bridge.file.enabled=false)");
            return;
        }

        log.info("Starting file watcher service (polling mode, interval={}ms)...", fileConfig.getPollIntervalMs());

        // Initialize polling monitor and processor
        monitor = new FileAlterationMonitor(fileConfig.getPollIntervalMs());
        processorExecutor = Executors.newFixedThreadPool(2);

        // Register bootstrap directories (if any)
        for (String watchDir : fileConfig.getWatchDirectories()) {
            if (watchDir == null || watchDir.isBlank()) {
                continue;
            }
            registerDirectoryInternal(Paths.get(watchDir).normalize(), null, true);
        }

        running = true;
        try {
            monitor.start();
        } catch (Exception e) {
            throw new IOException("Failed to start file polling monitor", e);
        }

        // Start stability checker (runs periodically)
        stabilityChecker.scheduleWithFixedDelay(
                this::checkStableFiles,
                fileConfig.getFileStabilityTimeoutMs(),
                fileConfig.getPollIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        // Periodic directory rescan — safety net for runtime-registered observers
        // that the monitor's polling thread may miss due to CopyOnWriteArrayList
        // snapshot timing. Scans all registered directories for untracked files.
        stabilityChecker.scheduleWithFixedDelay(
                this::rescanAllDirectories,
                fileConfig.getPollIntervalMs() * 2,
                fileConfig.getPollIntervalMs() * 2,
                TimeUnit.MILLISECONDS
        );

        log.info("File watcher service started successfully with {} watched directories",
                registrationsByDirectory.size());
    }

    /**
     * Register a directory for runtime watching on behalf of a specific
     * analyzer. Multiple analyzers may share the same physical directory as
     * long as their {@code filePattern} values distinguish the files they
     * each claim. Each call appends a new {@link WatchRegistration} unless
     * an existing registration already matches {@code (dirPath, analyzerId)},
     * in which case it is replaced in-place (glob + observer refreshed).
     */
    public synchronized void addWatchDirectory(Path dirPath, String filePattern, String analyzerId) throws IOException {
        Path normalized = dirPath.normalize();
        String effectiveGlob = (filePattern == null || filePattern.isBlank()) ? "*" : filePattern;
        registerDirectoryInternal(normalized, analyzerId, effectiveGlob, true);
        if (!fileConfig.getWatchDirectories().contains(normalized.toString())) {
            List<String> mutableWatchDirs = new ArrayList<>(fileConfig.getWatchDirectories());
            mutableWatchDirs.add(normalized.toString());
            fileConfig.setWatchDirectories(mutableWatchDirs);
        }
        log.info("Runtime watch directory registered: {} (analyzerId={}, glob={})", normalized, analyzerId,
                effectiveGlob);
    }

    /**
     * Remove ALL analyzer registrations for the given directory. Used during
     * shutdown and programmatic tear-down where the entire path should stop
     * being watched. To remove a single analyzer's registration while leaving
     * others alive, use {@link #removeWatchRegistration(Path, String)}.
     */
    public synchronized boolean removeWatchDirectory(Path dirPath) {
        Path normalized = dirPath.normalize();
        List<WatchRegistration> registrations = registrationsByDirectory.remove(normalized);
        if (registrations == null || registrations.isEmpty()) {
            return false;
        }
        if (monitor != null) {
            for (WatchRegistration reg : registrations) {
                if (reg.observer() != null) {
                    monitor.removeObserver(reg.observer());
                }
            }
        }
        pendingDirectories.remove(normalized);
        List<String> mutableWatchDirs = new ArrayList<>(fileConfig.getWatchDirectories());
        mutableWatchDirs.remove(normalized.toString());
        fileConfig.setWatchDirectories(mutableWatchDirs);
        log.info("Runtime watch directory removed: {} ({} registration(s))", normalized, registrations.size());
        return true;
    }

    /**
     * Remove a single analyzer's registration for the given directory,
     * leaving other registrations at the same path untouched. Returns
     * {@code true} iff a registration was actually removed.
     * <p>
     * Preserves the directory entry in {@link #fileConfig}'s watch list
     * unless the last registration was removed — in which case the
     * filesystem entry is also removed (same semantics as removing the
     * whole directory).
     */
    public synchronized boolean removeWatchRegistration(Path dirPath, String analyzerId) {
        Path normalized = dirPath.normalize();
        List<WatchRegistration> registrations = registrationsByDirectory.get(normalized);
        if (registrations == null || registrations.isEmpty()) {
            return false;
        }
        WatchRegistration match = null;
        for (WatchRegistration reg : registrations) {
            if (Objects.equals(reg.analyzerId(), analyzerId)) {
                match = reg;
                break;
            }
        }
        if (match == null) {
            return false;
        }
        registrations.remove(match);
        if (monitor != null && match.observer() != null) {
            monitor.removeObserver(match.observer());
        }
        if (registrations.isEmpty()) {
            registrationsByDirectory.remove(normalized);
            pendingDirectories.remove(normalized);
            List<String> mutableWatchDirs = new ArrayList<>(fileConfig.getWatchDirectories());
            mutableWatchDirs.remove(normalized.toString());
            fileConfig.setWatchDirectories(mutableWatchDirs);
        }
        log.info("Removed watch registration for analyzer {} at {}", analyzerId, normalized);
        return true;
    }

    /**
     * Remove all registrations owned by the given analyzer ID across every
     * watched directory.
     */
    public synchronized int removeWatchDirectoriesByAnalyzerId(String analyzerId) {
        int removed = 0;
        for (Path dirPath : new ArrayList<>(registrationsByDirectory.keySet())) {
            if (removeWatchRegistration(dirPath, analyzerId)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Bootstrap overload used by {@link #start()} when registering directories
     * from {@link FileConfig#getWatchDirectories()}. Bootstrap registrations
     * do not carry a per-analyzer glob — the directory is watched with a
     * catch-all glob and filtering falls back to the legacy
     * {@link FileConfig#getFilePatterns()} list evaluated in
     * {@link #shouldProcessFile}.
     */
    private void registerDirectoryInternal(Path dirPath, String analyzerId, boolean processExisting)
            throws IOException {
        registerDirectoryInternal(dirPath, analyzerId, "*", processExisting);
    }

    /**
     * Register (or re-register) a directory with the polling monitor on
     * behalf of one analyzer. Appends a new {@link WatchRegistration} to the
     * list keyed on {@code dirPath}, allowing multiple analyzers to share the
     * same physical directory. If a registration for {@code (dirPath,
     * analyzerId)} already exists, its observer is removed and replaced in
     * place so the glob + filter refresh.
     * <p>
     * The observer's {@link IOFileFilter} captures this registration's own
     * glob in the closure — that is the source of truth for "does this file
     * belong to this analyzer" so the Commons IO monitor only fires events
     * for files that match the registration's own pattern, independent of
     * other registrations at the same path.
     */
    private void registerDirectoryInternal(Path dirPath, String analyzerId, String globPattern,
            boolean processExisting) throws IOException {
        String effectiveGlob = (globPattern == null || globPattern.isBlank()) ? "*" : globPattern;

        if (!Files.exists(dirPath)) {
            log.warn("Watch directory does not exist, creating: {}", dirPath);
            try {
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                log.error("Cannot create watch directory {} — volume may not be mounted. "
                        + "Will retry when directory appears.", dirPath);
                if (analyzerId != null && !analyzerId.isBlank()) {
                    // Record the intended registration as a placeholder so
                    // rescan can materialize it when the directory appears.
                    List<WatchRegistration> list = registrationsByDirectory
                            .computeIfAbsent(dirPath, k -> new CopyOnWriteArrayList<>());
                    list.removeIf(reg -> Objects.equals(reg.analyzerId(), analyzerId));
                    list.add(new WatchRegistration(analyzerId, effectiveGlob, null));
                }
                pendingDirectories.add(dirPath);
                return;
            }
        }

        List<WatchRegistration> list = registrationsByDirectory.computeIfAbsent(dirPath,
                k -> new CopyOnWriteArrayList<>());

        // If a prior registration for this analyzer exists, remove its
        // observer so we don't leak observers across replace calls.
        WatchRegistration existing = null;
        for (WatchRegistration reg : list) {
            if (Objects.equals(reg.analyzerId(), analyzerId)) {
                existing = reg;
                break;
            }
        }
        if (existing != null) {
            list.remove(existing);
            if (existing.observer() != null && monitor != null) {
                monitor.removeObserver(existing.observer());
            }
        }

        // Build a filter that only accepts files matching THIS registration's
        // glob. The closure captures effectiveGlob, so two observers at the
        // same dir with different globs do not cross-fire events.
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + effectiveGlob);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid glob pattern '{}' for {} — falling back to catch-all", effectiveGlob, dirPath);
            matcher = FileSystems.getDefault().getPathMatcher("glob:*");
        }
        final PathMatcher finalMatcher = matcher;
        IOFileFilter fileFilter = new IOFileFilter() {
            @Override
            public boolean accept(File file) {
                if (!file.isFile()) {
                    return false;
                }
                Path filePath = file.toPath();
                if (!matchesBaseFilter(filePath)) {
                    return false;
                }
                return finalMatcher.matches(filePath.getFileName());
            }

            @Override
            public boolean accept(File dir, String name) {
                return accept(new File(dir, name));
            }
        };

        FileAlterationObserver observer = new FileAlterationObserver(dirPath.toFile(), fileFilter);
        observer.addListener(new FileAlterationListenerAdaptor() {
            @Override
            public void onFileCreate(File file) {
                trackFileForStability(file.toPath());
            }

            @Override
            public void onFileChange(File file) {
                trackFileForStability(file.toPath());
            }
        });

        // Initialize observer so its first poll has a baseline snapshot
        try {
            observer.initialize();
        } catch (Exception e) {
            log.warn("Failed to initialize observer for {}: {}", dirPath, e.getMessage());
        }

        list.add(new WatchRegistration(analyzerId, effectiveGlob, observer));
        if (monitor != null) {
            monitor.addObserver(observer);
        }

        log.info("Registered watch directory: {} (analyzerId={}, glob={})", dirPath, analyzerId, effectiveGlob);
        if (processExisting && processorExecutor != null) {
            processExistingFiles(dirPath);
        }
    }

    /**
     * Base-level file filter applied by every observer: skips hidden files
     * and legacy {@code .error} / {@code .failed} sidecars. The non-
     * destructive bridge never writes these, but a previous bridge version
     * may have left them behind. Per-registration glob matching runs AFTER
     * this base filter.
     */
    private boolean matchesBaseFilter(Path filePath) {
        String filename = filePath.getFileName().toString();
        if (filename.startsWith(".") || filename.endsWith(".error") || filename.endsWith(".failed")) {
            return false;
        }
        return true;
    }

    /**
     * Track a file for stability checking.
     * <p>
     * Records the file's current metadata (lastModified, size) so the stability
     * checker can determine when the file has stopped changing.
     * </p>
     */
    private void trackFileForStability(Path filePath) {
        if (Files.isDirectory(filePath)) {
            return;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            fileStabilityTracker.put(filePath, new FileMetadata(
                    attrs.lastModifiedTime().toInstant(),
                    attrs.size()
            ));
            log.debug("Tracking file for stability: {}", filePath.getFileName());
        } catch (IOException e) {
            log.warn("Failed to read file attributes for: {}", filePath, e);
        }
    }

    /**
     * Stop file watcher service with graceful shutdown.
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping file watcher service...");
        running = false;

        // Stop polling monitor
        if (monitor != null) {
            try {
                monitor.stop();
            } catch (Exception e) {
                log.error("Error stopping file alteration monitor", e);
            }
        }

        // Shutdown executors gracefully
        shutdownExecutor(processorExecutor, "processor");
        shutdownExecutor(stabilityChecker, "stability-checker");

        // State store is a shared @Bean; Spring closes it via
        // StateStoreConfig#fileStateStore(destroyMethod="close") during
        // ApplicationContext shutdown. FileWatcher must NOT close it here
        // because HttpForwardingRouter may still be using it.

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
     * Check for stable files and process them.
     * <p>
     * Stability is determined by watching a file's size + lastModifiedTime stay
     * unchanged for {@link FileConfig#getFileStabilityTimeoutMs()} milliseconds.
     * Stable files are submitted to {@link #processFileWithRetry(Path)} which
     * consults the state store to decide whether to actually process them.
     * </p>
     */
    private void checkStableFiles() {
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
                    // File is stable - size matches and timestamp within tolerance.
                    // Docker bind mounts can have ~1s timestamp granularity difference
                    // between host write time and container read time.
                    long timestampDiffMs = Math.abs(
                            currentLastModified.toEpochMilli() - metadata.lastModified.toEpochMilli());
                    if (currentSize == metadata.size && timestampDiffMs <= 1000) {
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
     * Periodic rescan of all registered directories. Safety net for files that
     * the monitor's observer-based polling may miss (e.g., runtime-added observers
     * not yet visible to the polling thread's snapshot).
     * <p>
     * Also materializes "placeholder" {@link WatchRegistration} entries whose
     * observer is null because the target directory did not exist at
     * registration time — a common case in Docker bind-mount deployments
     * where {@code /mnt/...} appears after the bridge container starts.
     */
    private void rescanAllDirectories() {
        // Retry pending directories that failed creation during registration.
        // A pending dir has one or more placeholder registrations
        // (observer=null) that we need to materialize now that the path is
        // accessible.
        for (Path pending : new ArrayList<>(pendingDirectories)) {
            if (!Files.exists(pending)) {
                continue;
            }
            log.info("Pending directory now exists, registering: {}", pending);
            pendingDirectories.remove(pending);
            List<WatchRegistration> placeholders = registrationsByDirectory.get(pending);
            if (placeholders == null) {
                continue;
            }
            // Snapshot placeholders so we can iterate safely while replacing.
            List<WatchRegistration> snapshot = new ArrayList<>(placeholders);
            for (WatchRegistration placeholder : snapshot) {
                if (placeholder.observer() != null) {
                    continue;
                }
                try {
                    registerDirectoryInternal(pending, placeholder.analyzerId(), placeholder.globPattern(), true);
                } catch (IOException e) {
                    log.warn("Failed to register pending directory {} for analyzer {}: {}", pending,
                            placeholder.analyzerId(), e.getMessage());
                }
            }
        }

        for (Path dir : registrationsByDirectory.keySet()) {
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> candidates = files
                        .filter(Files::isRegularFile)
                        .filter(this::shouldProcessFile)
                        .filter(file -> !fileStabilityTracker.containsKey(file))
                        .collect(java.util.stream.Collectors.toList());

                if (!candidates.isEmpty()) {
                    log.info("Rescan found {} untracked file(s) in {}", candidates.size(), dir);
                    candidates.forEach(this::trackFileForStability);
                }
            } catch (IOException e) {
                // Directory may not exist yet (pre-registration); ignore
            }
        }
    }

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
     * Process a file, consulting the {@link FileStateStore} for dedupe / retry
     * decisions. The bridge NEVER deletes or moves files — all outcomes are
     * recorded as state store metadata.
     * <p>
     * Flow:
     * <ol>
     *   <li>Acquire in-process lock (prevents two threads racing on the same path)</li>
     *   <li>Determine analyzerId; hash file content (SHA-256, streaming)</li>
     *   <li>Look up {@code (analyzerId, hash)} in the state store:
     *     <ul>
     *       <li>{@code PROCESSED} — touch last_seen, return (idempotent re-drop)</li>
     *       <li>{@code FAILED_NEEDS_HANDLING} — touch last_seen, return (terminal)</li>
     *       <li>{@code RETRYING} with future {@code next_attempt_at} — return (backoff)</li>
     *       <li>otherwise — upsertRetrying and proceed</li>
     *     </ul>
     *   </li>
     *   <li>Forward to {@code messageHandler.processFile()} (FHIR POST)</li>
     *   <li>On success: {@code markProcessed}. On exception: {@code handleProcessingFailure}.</li>
     * </ol>
     */
    private void processFileWithRetry(Path filePath) {
        // In-process lock: prevent two threads in this JVM from processing
        // the same path concurrently. Persistent retry scheduling is handled
        // by the state store's next_attempt_at; this set is purely for
        // intra-JVM races (e.g. rescan vs. scheduled retry firing near-simultaneously).
        if (!processingFiles.add(filePath)) {
            log.debug("File already being processed by another thread: {}", filePath.getFileName());
            return;
        }

        String analyzerId = null;
        String fileHash = null;
        try {
            analyzerId = determineAnalyzerId(filePath);
            if (analyzerId == null) {
                log.warn("Could not determine analyzer for file {}; skipping", filePath);
                return;
            }

            fileHash = calculateFileHash(filePath);
            MDC.put("analyzerId", analyzerId);
            MDC.put("contentHash", fileHash);
            MDC.put("path", filePath.toString());

            // Consult the state store for dedupe / backoff decisions.
            var existing = stateStore.get(analyzerId, fileHash);
            if (existing.isPresent()) {
                FileProcessingState state = existing.get();
                switch (state.status()) {
                    case PROCESSED -> {
                        stateStore.touchLastSeen(analyzerId, fileHash, filePath);
                        log.info("Skipping already-processed file (hash match): {}", filePath.getFileName());
                        return;
                    }
                    case FAILED_NEEDS_HANDLING -> {
                        stateStore.touchLastSeen(analyzerId, fileHash, filePath);
                        log.debug("Skipping file in FAILED_NEEDS_HANDLING: {} (last error: {})",
                                filePath.getFileName(), state.lastError());
                        return;
                    }
                    case RETRYING -> {
                        Instant nextAt = state.nextAttemptAt();
                        if (nextAt != null && Instant.now().isBefore(nextAt)) {
                            log.debug("Retry backoff not elapsed for {} (next at {})",
                                    filePath.getFileName(), nextAt);
                            return;
                        }
                        // Backoff elapsed (or null) — proceed with retry
                    }
                }
            }

            stateStore.upsertRetrying(analyzerId, fileHash, filePath);

            log.info("Processing file: {} for analyzer: {}", filePath.getFileName(), analyzerId);
            messageHandler.processFile(filePath, analyzerId);

            stateStore.markProcessed(analyzerId, fileHash, filePath);
            log.info("Successfully processed file: {}", filePath.getFileName());

        } catch (Exception e) {
            if (analyzerId != null && fileHash != null) {
                handleProcessingFailure(filePath, analyzerId, fileHash, e);
            } else {
                log.error("File processing failed before state store keying was possible for {}: {}",
                        filePath, e.getMessage(), e);
            }
        } finally {
            processingFiles.remove(filePath);
            MDC.remove("analyzerId");
            MDC.remove("contentHash");
            MDC.remove("path");
        }
    }

    /**
     * Record a processing failure in the state store and either schedule a
     * backoff retry or transition the row to {@code FAILED_NEEDS_HANDLING}.
     * <p>
     * Never touches the file. On max retries the structured audit line
     * {@code ANALYZER_FILE_FAILED_NEEDS_HANDLING} is logged for future
     * notifier / alerting hooks to consume.
     * </p>
     */
    private void handleProcessingFailure(Path filePath, String analyzerId, String fileHash, Exception error) {
        int attempts = stateStore.incrementAttempts(analyzerId, fileHash, error.getMessage());
        int max = fileConfig.getMaxRetryAttempts();
        log.warn("File processing failed (attempt {}/{}): {} - {}",
                attempts, max, filePath.getFileName(), error.getMessage());

        if (attempts >= max) {
            stateStore.markFailedNeedsHandling(analyzerId, fileHash, filePath, error.getMessage());
            // Grep-friendly audit line. A future notifier can watch the bridge
            // logs for this token and open tickets / send Slack messages.
            log.error("ANALYZER_FILE_FAILED_NEEDS_HANDLING analyzerId={} contentHash={} path={} attempts={} error={}",
                    analyzerId, fileHash, filePath, attempts, error.getMessage());
            return;
        }

        long delay = fileConfig.getRetryDelayMs() * (long) Math.pow(2, attempts - 1);
        Instant nextAt = Instant.now().plusMillis(delay);
        stateStore.setNextAttemptAt(analyzerId, fileHash, nextAt);
        log.info("Scheduling retry for file: {} in {}ms (next_attempt_at={})",
                filePath.getFileName(), delay, nextAt);
        stabilityChecker.schedule(() -> processFileWithRetry(filePath), delay, TimeUnit.MILLISECONDS);
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
     * Determine the analyzer ID that owns a given file path.
     * <p>
     * Lookup order (first match wins):
     * <ol>
     *   <li>Per-directory registrations: iterate the parent directory's
     *       {@link WatchRegistration} list and return the first one whose
     *       glob pattern matches the file name. This is how multi-observer
     *       directories route: each analyzer declared its own glob at
     *       registration time, and only one should match any given file.</li>
     *   <li>Legacy {@code fileConfig.getAnalyzers()} pattern map — kept as a
     *       fallback for bootstrap-registered analyzers that came in through
     *       the config file rather than runtime registration.</li>
     *   <li>Directory name as last resort.</li>
     * </ol>
     *
     * @param filePath the file path to analyze
     * @return analyzer ID or null if cannot be determined
     */
    private String determineAnalyzerId(Path filePath) {
        String pathString = filePath.toString();
        String filename = filePath.getFileName().toString();

        Path parent = filePath.getParent();
        if (parent != null) {
            List<WatchRegistration> registrations = registrationsByDirectory.get(parent.normalize());
            if (registrations != null) {
                for (WatchRegistration reg : registrations) {
                    if (reg.analyzerId() == null || reg.analyzerId().isBlank()) {
                        continue;
                    }
                    try {
                        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + reg.globPattern());
                        if (matcher.matches(filePath.getFileName())) {
                            log.debug("Matched file {} to analyzer {} via per-directory glob: {}", filename,
                                    reg.analyzerId(), reg.globPattern());
                            return reg.analyzerId();
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid per-registration glob '{}' for analyzer {}: {}", reg.globPattern(),
                                reg.analyzerId(), e.getMessage());
                    }
                }
            }
        }

        // Check configured analyzer patterns (legacy fallback for bootstrap
        // registrations via FileConfig rather than runtime addWatchDirectory).
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
     * Check if a file should be processed by ANY registered analyzer.
     * <p>
     * Skips hidden files and any legacy {@code .error} / {@code .failed}
     * sidecars that a previous (destructive) bridge version may have left
     * in the watched directory. Then checks each registration at the parent
     * directory — returns true if ANY registration's glob matches. This is
     * used by the rescan safety net and by existing-file bootstrap walks;
     * the per-observer IOFileFilter captured at registration time is what
     * actually routes events to individual analyzers once the monitor is
     * running.
     * </p>
     */
    private boolean shouldProcessFile(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return false;
        }

        if (!matchesBaseFilter(filePath)) {
            return false;
        }

        Path parent = filePath.getParent();
        if (parent != null) {
            List<WatchRegistration> registrations = registrationsByDirectory.get(parent.normalize());
            if (registrations != null && !registrations.isEmpty()) {
                for (WatchRegistration reg : registrations) {
                    try {
                        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + reg.globPattern());
                        if (matcher.matches(filePath.getFileName())) {
                            return true;
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid glob '{}' for {}: {}", reg.globPattern(), parent, e.getMessage());
                    }
                }
                // A directory with registrations is opinionated: if no glob
                // matched, the file belongs to no analyzer at this path.
                return false;
            }
        }

        // No per-directory registrations — fall back to the bootstrap
        // fileConfig.getFilePatterns() list (e.g. *.csv, *.hl7) for legacy
        // config-file-driven analyzers.
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
     * Test-only accessor for the durable state store. Not part of the public
     * API — used by integration tests to inspect RETRYING / PROCESSED / FAILED
     * rows after a simulated drop.
     */
    public FileStateStore getStateStore() {
        return stateStore;
    }
}
