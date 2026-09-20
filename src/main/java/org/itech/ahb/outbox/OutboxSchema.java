package org.itech.ahb.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.itech.ahb.store.SqliteSupport;

/** Versioned schema for the delivery outbox. */
final class OutboxSchema {

  private OutboxSchema() {}

  static List<SqliteSupport.Migration> migrations() {
    return List.of(v1());
  }

  private static SqliteSupport.Migration v1() {
    return new SqliteSupport.Migration() {
      @Override
      public int version() {
        return 1;
      }

      @Override
      public String name() {
        return "create-outbox";
      }

      @Override
      public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
          // Received content is stored once per distinct payload: one CSV body can produce many
          // deliveries, and storing it per delivery would multiply clinical data on disk.
          st.execute(
            """
            CREATE TABLE outbox_raw (
              raw_hash    TEXT NOT NULL PRIMARY KEY,
              raw_text    TEXT NOT NULL,
              raw_charset TEXT NOT NULL,
              byte_length INTEGER NOT NULL,
              created_at  TEXT NOT NULL
            )
            """
          );
          st.execute(
            """
            CREATE TABLE outbox (
              id                    TEXT NOT NULL PRIMARY KEY,
              state                 TEXT NOT NULL CHECK (state IN ('RECEIVED','PENDING','RETRYING','DELIVERED','DMQ')),
              raw_hash              TEXT NOT NULL REFERENCES outbox_raw(raw_hash),
              fhir_json             TEXT,
              fhir_hash             TEXT,
              connection_id         TEXT,
              analyzer_id           TEXT,
              source_id             TEXT NOT NULL,
              source_port           INTEGER,
              protocol              TEXT NOT NULL,
              transport             TEXT NOT NULL,
              protocol_hint         TEXT,
              profile_id            TEXT,
              profile_revision      INTEGER,
              accession             TEXT,
              target_uri            TEXT,
              attempts              INTEGER NOT NULL DEFAULT 0,
              received_at           TEXT NOT NULL,
              rendered_at           TEXT,
              next_attempt_at       TEXT,
              last_attempt_at       TEXT,
              delivered_at          TEXT,
              dmq_at                TEXT,
              lease_until           TEXT,
              lease_owner           TEXT,
              failure_reason        TEXT,
              last_error            TEXT,
              last_http_status      INTEGER,
              last_response_excerpt TEXT,
              oe_receipt            TEXT,
              dismissed_at          TEXT,
              dismissed_by          TEXT,
              retry_requested_by    TEXT,
              retry_requested_at    TEXT,
              updated_at            TEXT NOT NULL
            )
            """
          );
          // The dispatcher's claim query: due work, oldest first.
          st.execute("CREATE INDEX idx_outbox_due ON outbox (state, next_attempt_at)");
          st.execute("CREATE INDEX idx_outbox_received ON outbox (received_at DESC)");
          st.execute("CREATE INDEX idx_outbox_connection ON outbox (connection_id, received_at DESC)");
          st.execute("CREATE INDEX idx_outbox_raw ON outbox (raw_hash)");
          st.execute(
            """
            CREATE TABLE outbox_attempt (
              id               INTEGER PRIMARY KEY AUTOINCREMENT,
              outbox_id        TEXT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
              attempt_no       INTEGER NOT NULL,
              kind             TEXT NOT NULL,
              actor            TEXT,
              started_at       TEXT NOT NULL,
              finished_at      TEXT,
              outcome          TEXT,
              http_status      INTEGER,
              error            TEXT,
              response_excerpt TEXT
            )
            """
          );
          st.execute("CREATE INDEX idx_outbox_attempt ON outbox_attempt (outbox_id, attempt_no)");
        }
      }
    };
  }
}
