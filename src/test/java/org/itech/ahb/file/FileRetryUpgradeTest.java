package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An old metadata-only retry has no trustworthy manual assay context; an upgrade must not invent it. */
class FileRetryUpgradeTest {

  @TempDir
  Path directory;

  @Test
  void holdsOldUnresolvedWorkOnceAndPreservesEvidenceAndNewReceiptRetries() throws Exception {
    Path db = directory.resolve("old.db");
    Path source = Files.writeString(directory.resolve("old.csv"), "original export");
    try (
      var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
      var statement = connection.createStatement()
    ) {
      statement.execute(
        "CREATE TABLE file_state (analyzer_id TEXT NOT NULL, content_hash TEXT NOT NULL, status TEXT NOT NULL, " +
        "last_path TEXT NOT NULL, first_seen TEXT NOT NULL, last_seen TEXT NOT NULL, last_attempt TEXT, next_attempt_at TEXT, " +
        "attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, PRIMARY KEY(analyzer_id, content_hash))"
      );
      try (
        var insert = connection.prepareStatement(
          "INSERT INTO file_state VALUES ('oe','hash','RETRYING',?,?,?,?,?,4,'network failed')"
        )
      ) {
        insert.setString(1, source.toString());
        for (int i = 2; i <= 5; i++) insert.setString(i, Instant.EPOCH.toString());
        insert.executeUpdate();
      }
    }
    SqliteFileStateStore store = new SqliteFileStateStore(db);
    try {
      var held = store.get("oe", "hash").orElseThrow();
      assertEquals(FileProcessingState.Status.FAILED_NEEDS_HANDLING, held.status());
      assertEquals(4, held.attempts());
      assertEquals(source.toString(), held.lastPath());
      assertEquals(Instant.EPOCH, held.firstSeen());
      assertTrue(held.lastError().contains("network failed"));
      assertTrue(held.lastError().contains("selected assay"));
      assertNull(held.nextAttemptAt());
      assertEquals("original export", Files.readString(source));
      store.upsertRetrying("oe", "new-receipt", directory.resolve("new.csv"));
    } finally {
      store.close();
    }
    store = new SqliteFileStateStore(db);
    try {
      assertEquals(
        FileProcessingState.Status.RETRYING,
        store.get("oe", "new-receipt").orElseThrow().status(),
        "the upgrade hold must not rerun on new capture retries"
      );
      assertEquals(4, store.get("oe", "hash").orElseThrow().attempts());
    } finally {
      store.close();
    }
  }
}
