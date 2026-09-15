-- MySQL 8 host-managed migration, equivalent to JdbcRunStore.initializeSchema().
-- Apply to a dedicated harness database using a migration account.
CREATE TABLE IF NOT EXISTS harness_schema (
  component VARCHAR(64) PRIMARY KEY,
  schema_version INTEGER NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS harness_runs (
  run_id VARCHAR(128) PRIMARY KEY,
  creation_scope VARCHAR(64) NOT NULL UNIQUE,
  creation_digest VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL,
  fence BIGINT NOT NULL,
  lease_until BIGINT,
  run_status VARCHAR(32) NOT NULL,
  next_attempt_at BIGINT,
  created_at BIGINT NOT NULL,
  snapshot_json LONGTEXT NOT NULL,
  event_sequence BIGINT NOT NULL,
  INDEX harness_runs_ready_idx (run_status, next_attempt_at, lease_until, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS harness_run_events (
  run_id VARCHAR(128) NOT NULL,
  event_sequence BIGINT NOT NULL,
  event_json LONGTEXT NOT NULL,
  PRIMARY KEY (run_id, event_sequence),
  FOREIGN KEY (run_id) REFERENCES harness_runs(run_id)
) ENGINE=InnoDB;

INSERT INTO harness_schema (component, schema_version) VALUES ('run-store', 1);
