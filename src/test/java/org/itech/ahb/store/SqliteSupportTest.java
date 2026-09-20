package org.itech.ahb.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.itech.ahb.store.SqliteSupport.Migration;
import org.itech.ahb.store.SqliteSupport.OpenResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the plumbing the delivery outbox depends on: durability pragmas, forward-only
 * {@code user_version} migrations, and corruption recovery.
 */
class SqliteSupportTest {

  private static Migration migration(int version, String name, String ddl, AtomicInteger counter) {
    return new Migration() {
      @Override
      public int version() {
        return version;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public void apply(Connection connection) throws SQLException {
        counter.incrementAndGet();
        try (Statement st = connection.createStatement()) {
          st.execute(ddl);
        }
      }
    };
  }

  private static boolean tableExists(Connection c, String table) throws SQLException {
    try (
      Statement st = c.createStatement();
      ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
    ) {
      while (rs.next()) {
        if (table.equals(rs.getString(1))) {
          return true;
        }
      }
    }
    return false;
  }

  private static String pragma(Connection c, String name) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  @Nested
  @DisplayName("open")
  class Open {

    @Test
    @DisplayName("applies WAL and the requested durability level")
    void appliesPragmas(@TempDir Path dir) throws Exception {
      try (Connection c = SqliteSupport.openWithPragmas(dir.resolve("full.db"), SqliteSupport.Synchronous.FULL)) {
        assertEquals("wal", pragma(c, "journal_mode").toLowerCase());
        // sqlite reports synchronous numerically: 1 = NORMAL, 2 = FULL.
        assertEquals("2", pragma(c, "synchronous"), "outbox durability requires synchronous=FULL");
      }
      try (Connection c = SqliteSupport.openWithPragmas(dir.resolve("normal.db"), SqliteSupport.Synchronous.NORMAL)) {
        assertEquals("1", pragma(c, "synchronous"));
      }
    }

    @Test
    @DisplayName("creates the parent directory")
    void createsParentDirectory(@TempDir Path dir) {
      Path nested = dir.resolve("a/b/c/state.db");
      Path prepared = SqliteSupport.prepareDirectory(nested, "TestStore");
      assertTrue(Files.isDirectory(prepared.getParent()));
      assertTrue(prepared.isAbsolute());
    }

    @Test
    @DisplayName("renames a corrupt database aside and reports the recovery")
    void recoversFromCorruption(@TempDir Path dir) throws Exception {
      Path db = dir.resolve("corrupt.db");
      Files.write(db, "this is definitely not a sqlite database".getBytes(StandardCharsets.UTF_8));

      OpenResult result = SqliteSupport.openOrRecover(
        db,
        "TestStore",
        SqliteSupport.Synchronous.FULL,
        "Nothing is lost."
      );

      assertTrue(result.recoveredFromCorruption(), "a non-sqlite file must be classified as corruption");
      assertNotNull(result.corruptBackup());
      assertNotNull(result.corruptionRecoveredAt());
      assertTrue(Files.exists(result.corruptBackup()), "the damaged file must be preserved for forensics");
      try (Connection c = result.connection()) {
        assertEquals(0, SqliteSupport.userVersion(c), "recovery yields a fresh, unmigrated database");
      }
    }

    @Test
    @DisplayName("opens a healthy database without recovering")
    void opensHealthyDatabase(@TempDir Path dir) throws Exception {
      Path db = dir.resolve("healthy.db");
      SqliteSupport.openWithPragmas(db, SqliteSupport.Synchronous.FULL).close();

      OpenResult result = SqliteSupport.openOrRecover(db, "TestStore", SqliteSupport.Synchronous.FULL, "n/a");

      assertFalse(result.recoveredFromCorruption());
      result.connection().close();
    }
  }

  @Nested
  @DisplayName("migrate")
  class Migrate {

    @Test
    @DisplayName("applies pending steps once and records the version")
    void appliesOnce(@TempDir Path dir) throws Exception {
      Path db = dir.resolve("migrate.db");
      AtomicInteger applied = new AtomicInteger();
      List<Migration> v1 = List.of(migration(1, "create-outbox", "CREATE TABLE outbox (id TEXT PRIMARY KEY)", applied));

      try (Connection c = SqliteSupport.openWithPragmas(db, SqliteSupport.Synchronous.FULL)) {
        assertEquals(1, SqliteSupport.migrate(c, "TestStore", v1));
        assertTrue(tableExists(c, "outbox"));
        assertEquals(1, applied.get());
      }

      // Reopening an existing store must not replay a step that already ran.
      try (Connection c = SqliteSupport.openWithPragmas(db, SqliteSupport.Synchronous.FULL)) {
        assertEquals(1, SqliteSupport.migrate(c, "TestStore", v1));
        assertEquals(1, applied.get(), "an already-applied migration must not run again");
      }
    }

    @Test
    @DisplayName("applies only the steps above the current version")
    void appliesOnlyPendingSteps(@TempDir Path dir) throws Exception {
      Path db = dir.resolve("upgrade.db");
      AtomicInteger first = new AtomicInteger();
      AtomicInteger second = new AtomicInteger();
      Migration m1 = migration(1, "create", "CREATE TABLE outbox (id TEXT PRIMARY KEY)", first);
      Migration m2 = migration(2, "add-column", "ALTER TABLE outbox ADD COLUMN state TEXT", second);

      try (Connection c = SqliteSupport.openWithPragmas(db, SqliteSupport.Synchronous.FULL)) {
        SqliteSupport.migrate(c, "TestStore", List.of(m1));
      }
      try (Connection c = SqliteSupport.openWithPragmas(db, SqliteSupport.Synchronous.FULL)) {
        assertEquals(2, SqliteSupport.migrate(c, "TestStore", List.of(m1, m2)));
        assertEquals(1, first.get(), "V1 must not re-run on an upgraded store");
        assertEquals(1, second.get());
      }
    }

    @Test
    @DisplayName("rolls a failing step back and leaves the version untouched")
    void rollsBackFailedStep(@TempDir Path dir) throws Exception {
      Path db = dir.resolve("failing.db");
      AtomicInteger applied = new AtomicInteger();
      Migration good = migration(1, "create", "CREATE TABLE outbox (id TEXT PRIMARY KEY)", applied);
      Migration broken = new Migration() {
        @Override
        public int version() {
          return 2;
        }

        @Override
        public String name() {
          return "broken";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
          try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE half (id TEXT PRIMARY KEY)");
            st.execute("THIS IS NOT SQL");
          }
        }
      };

      try (Connection c = SqliteSupport.openWithPragmas(db, SqliteSupport.Synchronous.FULL)) {
        assertThrows(IllegalStateException.class, () -> SqliteSupport.migrate(c, "TestStore", List.of(good, broken)));
        assertEquals(1, SqliteSupport.userVersion(c), "a failed step must not advance the schema version");
        assertTrue(tableExists(c, "outbox"), "the step that succeeded stays applied");
        assertFalse(tableExists(c, "half"), "the failed step must be rolled back whole");
      }
    }

    @Test
    @DisplayName("rejects a duplicate migration version")
    void rejectsDuplicateVersions(@TempDir Path dir) throws Exception {
      AtomicInteger counter = new AtomicInteger();
      List<Migration> clashing = List.of(
        migration(1, "a", "CREATE TABLE a (id TEXT)", counter),
        migration(1, "b", "CREATE TABLE b (id TEXT)", counter)
      );
      try (Connection c = SqliteSupport.openWithPragmas(dir.resolve("dup.db"), SqliteSupport.Synchronous.FULL)) {
        assertThrows(IllegalArgumentException.class, () -> SqliteSupport.migrate(c, "TestStore", clashing));
        assertEquals(0, counter.get(), "validation must run before any DDL");
      }
    }
  }
}
