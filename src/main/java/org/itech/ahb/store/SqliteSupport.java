package org.itech.ahb.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared SQLite plumbing for the bridge's durable stores: directory preparation, connection open
 * with pragmas, corruption classification and rename-and-replace recovery, and {@code user_version}
 * schema migrations.
 *
 * <p>Extracted from {@code SqliteFileStateStore} so the delivery outbox can reuse exactly the same
 * open and recovery semantics without depending on the FILE transport's store or its
 * {@code bridge.file.enabled} gate.
 */
@Slf4j
public final class SqliteSupport {

  private SqliteSupport() {}

  /** {@code PRAGMA synchronous} level for a store. */
  public enum Synchronous {
    /**
     * WAL default. A crash of the host OS can lose the most recent commits. Adequate for state that
     * can be rebuilt by rescanning a source of truth.
     */
    NORMAL,
    /**
     * fsync on every commit. Required where the database IS the source of truth and a lost commit
     * means lost clinical data.
     */
    FULL
  }

  /** One forward-only schema step, applied when {@code PRAGMA user_version} is below its version. */
  public interface Migration {
    /** 1-based, strictly increasing across a store's migration list. */
    int version();

    /** Human-readable name, logged when the step runs. */
    String name();

    /** Apply the DDL. Runs inside a transaction; throwing rolls the step back. */
    void apply(Connection connection) throws SQLException;
  }

  /**
   * Outcome of {@link #openOrRecover}. When {@code corruptBackup} is non-null the previous database
   * was unreadable and has been renamed aside; the returned connection is to a fresh empty file and
   * whatever it held is gone.
   */
  public record OpenResult(Connection connection, Path corruptBackup, Instant corruptionRecoveredAt) {
    public boolean recoveredFromCorruption() {
      return corruptBackup != null;
    }
  }

  /** Create the database file's parent directory, failing loudly if that is not possible. */
  public static Path prepareDirectory(Path dbPath, String storeName) {
    Path absolute = dbPath.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to create parent directory for " + storeName + ": " + absolute, e);
      }
    }
    return absolute;
  }

  /**
   * Open the database, recovering from corruption by renaming the damaged file aside and creating a
   * fresh one so the bridge keeps running.
   *
   * <p>Only corruption triggers the rename. Every other failure (missing driver, permission denied,
   * disk full, lock held) propagates, because destroying a database in response to an
   * operator-fixable problem would be a catastrophic over-reaction.
   *
   * @param corruptionConsequence one sentence, logged at ERROR, stating what the deployment loses
   *     when this particular store is replaced. Callers whose store is the only copy of clinical
   *     data must say so here.
   */
  public static OpenResult openOrRecover(
    Path dbPath,
    String storeName,
    Synchronous synchronous,
    String corruptionConsequence
  ) {
    try {
      return new OpenResult(openWithPragmas(dbPath, synchronous), null, null);
    } catch (SQLException e) {
      if (!isCorruptionException(e)) {
        log.error(
          "{} at {} failed to open with a NON-CORRUPTION SQLException. Refusing to rename-and-replace " +
          "the file. Investigate driver, filesystem permissions, disk state. Cause: {}",
          storeName,
          dbPath,
          e.getMessage(),
          e
        );
        throw new IllegalStateException(storeName + " failed to open (non-corruption error): " + dbPath, e);
      }
      log.error(
        "CRITICAL: {} database at {} is CORRUPT (error code {}): {}",
        storeName,
        dbPath,
        e.getErrorCode(),
        e.getMessage(),
        e
      );
      Instant recoveredAt = Instant.now();
      Path corrupt = dbPath.resolveSibling(
        dbPath.getFileName() + ".corrupt-" + recoveredAt.toString().replace(':', '-')
      );
      try {
        Files.move(dbPath, corrupt, StandardCopyOption.REPLACE_EXISTING);
        log.error(
          "CRITICAL: Renamed corrupt {} to {}. A fresh empty store will be created. {}",
          storeName,
          corrupt,
          corruptionConsequence
        );
      } catch (IOException moveErr) {
        throw new IllegalStateException(
          "Failed to rename corrupt " + storeName + " " + dbPath + " to " + corrupt,
          moveErr
        );
      }
      try {
        return new OpenResult(openWithPragmas(dbPath, synchronous), corrupt, recoveredAt);
      } catch (SQLException retryErr) {
        throw new IllegalStateException("Failed to open fresh " + storeName + " after corruption recovery", retryErr);
      }
    }
  }

  /**
   * Open a connection in WAL mode with the given durability level and probe it with {@code PRAGMA
   * integrity_check}, so a damaged file fails here rather than at first use.
   */
  public static Connection openWithPragmas(Path dbPath, Synchronous synchronous) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    try (Statement st = c.createStatement()) {
      st.execute("PRAGMA journal_mode = WAL");
      st.execute("PRAGMA synchronous = " + synchronous.name());
      st.execute("PRAGMA busy_timeout = 5000");
      st.execute("PRAGMA foreign_keys = ON");
    }
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
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

  /**
   * Classify an {@link SQLException} thrown during connection open as corruption (the file is
   * damaged or is not a SQLite database at all) versus anything else.
   *
   * <p>For xerial sqlite-jdbc corruption surfaces as {@code SQLITE_CORRUPT} (11) or {@code
   * SQLITE_NOTADB} (26). {@link #openWithPragmas} also throws a synthetic exception whose message
   * starts with {@code "Integrity check failed"}; that path carries no error code, so the classifier
   * falls back to message text.
   */
  public static boolean isCorruptionException(SQLException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof SQLException sql) {
        int code = sql.getErrorCode();
        if (code == 11 || code == 26) {
          return true;
        }
        String msg = sql.getMessage();
        if (
          msg != null &&
          (msg.startsWith("Integrity check failed") ||
            msg.contains("file is not a database") ||
            msg.contains("database disk image is malformed"))
        ) {
          return true;
        }
      }
      t = t.getCause();
    }
    return false;
  }

  /**
   * Apply every migration whose version exceeds the database's {@code user_version}, each in its own
   * transaction, then record the new version. A fresh database starts at 0, so V1 creates the
   * schema; an existing database skips what it already has.
   *
   * @return the resulting {@code user_version}
   */
  public static int migrate(Connection connection, String storeName, List<Migration> migrations) {
    List<Migration> ordered = new ArrayList<>(migrations);
    ordered.sort((a, b) -> Integer.compare(a.version(), b.version()));
    Set<Integer> seen = new HashSet<>();
    for (Migration m : ordered) {
      if (m.version() < 1) {
        throw new IllegalArgumentException(storeName + " migration versions start at 1: " + m.name());
      }
      if (!seen.add(m.version())) {
        throw new IllegalArgumentException(storeName + " has duplicate migration version " + m.version());
      }
    }
    try {
      int current = userVersion(connection);
      for (Migration m : ordered) {
        if (m.version() <= current) {
          continue;
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
          m.apply(connection);
          try (Statement st = connection.createStatement()) {
            // PRAGMA arguments cannot be bound; version() is validated above and is an int.
            st.execute("PRAGMA user_version = " + m.version());
          }
          connection.commit();
          log.info("{} migrated to schema version {} ({})", storeName, m.version(), m.name());
        } catch (SQLException e) {
          connection.rollback();
          throw new IllegalStateException(
            storeName + " migration " + m.version() + " (" + m.name() + ") failed and was rolled back",
            e
          );
        } finally {
          connection.setAutoCommit(autoCommit);
        }
        current = m.version();
      }
      return current;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to migrate " + storeName, e);
    }
  }

  /** Read {@code PRAGMA user_version} (0 on a database that has never been migrated). */
  public static int userVersion(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }
}
