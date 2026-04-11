package org.itech.ahb.file;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed {@link FileStateStore}.
 * <p>
 * Single-connection, single-writer. Opens the database with WAL journal mode
 * and a 5-second busy timeout so concurrent reads (e.g. the admin endpoint)
 * don't block writes from the file-processing thread.
 * </p>
 * <p>
 * <b>Corruption recovery:</b> if the database fails to open, the file is
 * renamed with a {@code .corrupt-<timestamp>} suffix and a fresh empty store
 * is created. The worst-case consequence is re-POSTing a small number of
 * already-processed files to OpenELIS; the FHIR upsert path is idempotent
 * on {@code (sampleAccession, testCode, analyzerId)} so this is safe.
 * </p>
 */
@Slf4j
public class SqliteFileStateStore implements FileStateStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS file_state (
              analyzer_id     TEXT NOT NULL,
              content_hash    TEXT NOT NULL,
              status          TEXT NOT NULL,
              last_path       TEXT NOT NULL,
              first_seen      TEXT NOT NULL,
              last_seen       TEXT NOT NULL,
              last_attempt    TEXT,
              next_attempt_at TEXT,
              attempts        INTEGER NOT NULL DEFAULT 0,
              last_error      TEXT,
              PRIMARY KEY (analyzer_id, content_hash)
            )
            """;

    private static final String SCHEMA_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_file_state_status ON file_state (status)";

    private final Path dbPath;
    private final Connection conn;

    /**
     * Open (or create) a SQLite database at the given path. The parent
     * directory is created if necessary. If the file exists but is corrupt,
     * it is renamed out of the way and a fresh store is created.
     */
    public SqliteFileStateStore(Path dbPath) {
        this.dbPath = dbPath.toAbsolutePath();
        try {
            Files.createDirectories(this.dbPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create parent directory for state store: " + this.dbPath, e);
        }
        this.conn = openOrRecover(this.dbPath);
        initializeSchema();
        log.info("FileStateStore opened at {} (WAL mode)", this.dbPath);
    }

    private static Connection openOrRecover(Path dbPath) {
        try {
            return openWithPragmas(dbPath);
        } catch (SQLException e) {
            log.error("CRITICAL: FileStateStore database at {} failed to open: {}",
                    dbPath, e.getMessage(), e);
            Path corrupt = dbPath.resolveSibling(
                    dbPath.getFileName() + ".corrupt-" + Instant.now().toString().replace(':', '-'));
            try {
                Files.move(dbPath, corrupt, StandardCopyOption.REPLACE_EXISTING);
                log.error("CRITICAL: Renamed corrupt state store to {}. A fresh empty store will be created. "
                        + "Already-processed files may be re-POSTed to OpenELIS; the FHIR upsert path is "
                        + "idempotent on (sampleAccession, testCode, analyzerId) so this is safe.", corrupt);
            } catch (IOException moveErr) {
                throw new IllegalStateException(
                        "Failed to rename corrupt state store " + dbPath + " to " + corrupt, moveErr);
            }
            try {
                return openWithPragmas(dbPath);
            } catch (SQLException retryErr) {
                throw new IllegalStateException(
                        "Failed to open fresh state store after corruption recovery", retryErr);
            }
        }
    }

    private static Connection openWithPragmas(Path dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath;
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA foreign_keys = ON");
        }
        // Quick integrity probe — causes SQLException on a corrupt file
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            if (rs.next()) {
                String result = rs.getString(1);
                if (!"ok".equalsIgnoreCase(result)) {
                    c.close();
                    throw new SQLException("Integrity check failed: " + result);
                }
            }
        }
        return c;
    }

    private void initializeSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute(SCHEMA);
            st.execute(SCHEMA_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize FileStateStore schema", e);
        }
    }

    @Override
    public Optional<FileProcessingState> get(String analyzerId, String contentHash) {
        String sql = "SELECT analyzer_id, content_hash, status, last_path, first_seen, last_seen, "
                + "last_attempt, next_attempt_at, attempts, last_error "
                + "FROM file_state WHERE analyzer_id = ? AND content_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, analyzerId);
            ps.setString(2, contentHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.get failed", e);
        }
        return Optional.empty();
    }

    @Override
    public void markProcessed(String analyzerId, String contentHash, Path path) {
        String now = TS.format(Instant.now());
        String sql = "INSERT INTO file_state "
                + "(analyzer_id, content_hash, status, last_path, first_seen, last_seen, last_attempt, "
                + "next_attempt_at, attempts, last_error) "
                + "VALUES (?, ?, 'PROCESSED', ?, ?, ?, ?, NULL, COALESCE((SELECT attempts FROM file_state "
                + "WHERE analyzer_id = ? AND content_hash = ?), 0), NULL) "
                + "ON CONFLICT (analyzer_id, content_hash) DO UPDATE SET "
                + "status = 'PROCESSED', last_path = excluded.last_path, last_seen = excluded.last_seen, "
                + "last_attempt = excluded.last_attempt, next_attempt_at = NULL, last_error = NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, analyzerId);
            ps.setString(2, contentHash);
            ps.setString(3, path.toAbsolutePath().toString());
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.setString(7, analyzerId);
            ps.setString(8, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.markProcessed failed", e);
        }
    }

    @Override
    public void upsertRetrying(String analyzerId, String contentHash, Path path) {
        String now = TS.format(Instant.now());
        String sql = "INSERT INTO file_state "
                + "(analyzer_id, content_hash, status, last_path, first_seen, last_seen, last_attempt, "
                + "next_attempt_at, attempts, last_error) "
                + "VALUES (?, ?, 'RETRYING', ?, ?, ?, ?, NULL, 0, NULL) "
                + "ON CONFLICT (analyzer_id, content_hash) DO UPDATE SET "
                + "status = 'RETRYING', last_path = excluded.last_path, last_seen = excluded.last_seen, "
                + "last_attempt = excluded.last_attempt";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, analyzerId);
            ps.setString(2, contentHash);
            ps.setString(3, path.toAbsolutePath().toString());
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.upsertRetrying failed", e);
        }
    }

    @Override
    public int incrementAttempts(String analyzerId, String contentHash, String errorMessage) {
        String sql = "UPDATE file_state SET attempts = attempts + 1, last_error = ?, last_attempt = ? "
                + "WHERE analyzer_id = ? AND content_hash = ?";
        String now = TS.format(Instant.now());
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, errorMessage);
            ps.setString(2, now);
            ps.setString(3, analyzerId);
            ps.setString(4, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.incrementAttempts failed", e);
        }
        return get(analyzerId, contentHash).map(FileProcessingState::attempts).orElse(0);
    }

    @Override
    public void setNextAttemptAt(String analyzerId, String contentHash, Instant at) {
        String sql = "UPDATE file_state SET next_attempt_at = ? "
                + "WHERE analyzer_id = ? AND content_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TS.format(at));
            ps.setString(2, analyzerId);
            ps.setString(3, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.setNextAttemptAt failed", e);
        }
    }

    @Override
    public void markFailedNeedsHandling(String analyzerId, String contentHash, Path path, String errorMessage) {
        String now = TS.format(Instant.now());
        String sql = "UPDATE file_state SET status = 'FAILED_NEEDS_HANDLING', last_error = ?, "
                + "last_path = ?, last_seen = ?, next_attempt_at = NULL "
                + "WHERE analyzer_id = ? AND content_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, errorMessage);
            ps.setString(2, path.toAbsolutePath().toString());
            ps.setString(3, now);
            ps.setString(4, analyzerId);
            ps.setString(5, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.markFailedNeedsHandling failed", e);
        }
    }

    @Override
    public void touchLastSeen(String analyzerId, String contentHash, Path path) {
        String sql = "UPDATE file_state SET last_seen = ?, last_path = ? "
                + "WHERE analyzer_id = ? AND content_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TS.format(Instant.now()));
            ps.setString(2, path.toAbsolutePath().toString());
            ps.setString(3, analyzerId);
            ps.setString(4, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.touchLastSeen failed", e);
        }
    }

    @Override
    public List<FileProcessingState> list(FileProcessingState.Status status, int limit, int offset) {
        String sql = "SELECT analyzer_id, content_hash, status, last_path, first_seen, last_seen, "
                + "last_attempt, next_attempt_at, attempts, last_error "
                + "FROM file_state WHERE status = ? ORDER BY last_seen DESC LIMIT ? OFFSET ?";
        List<FileProcessingState> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FileStateStore.list failed", e);
        }
        return out;
    }

    private static FileProcessingState fromRow(ResultSet rs) throws SQLException {
        return new FileProcessingState(
                rs.getString("analyzer_id"),
                rs.getString("content_hash"),
                FileProcessingState.Status.valueOf(rs.getString("status")),
                rs.getString("last_path"),
                parseTs(rs.getString("first_seen")),
                parseTs(rs.getString("last_seen")),
                parseTs(rs.getString("last_attempt")),
                parseTs(rs.getString("next_attempt_at")),
                rs.getInt("attempts"),
                rs.getString("last_error")
        );
    }

    private static Instant parseTs(String s) {
        return (s == null || s.isBlank()) ? null : Instant.parse(s);
    }

    /** Visible for shutdown + tests. */
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("Failed to close FileStateStore connection: {}", e.getMessage());
        }
    }

    /** Visible for tests — absolute path of the underlying SQLite file. */
    public Path getDbPath() {
        return dbPath;
    }
}
