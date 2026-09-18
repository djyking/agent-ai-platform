-- MySQL 8.0 platform schema v1. Apply after run-store schema v1 on a dedicated database.
-- Never wrap DDL and accepted Run commands in one transaction: MySQL commits DDL implicitly.
-- Matches PlatformRepository.initialize. The runtime refuses unknown schema versions.
CREATE TABLE IF NOT EXISTS platform_scope_locks (
  scope_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_releases (
  project_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  release_id VARCHAR(36) NOT NULL, digest VARCHAR(80) NOT NULL,
  manifest_json LONGTEXT NOT NULL, PRIMARY KEY(project_id,release_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_runs (
  run_id VARCHAR(128) PRIMARY KEY,
  project_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  application_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  subject_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  delegation_id VARCHAR(128) NOT NULL, release_json LONGTEXT NOT NULL,
  limits_json LONGTEXT NOT NULL, client_reference VARCHAR(200),
  created_at BIGINT NOT NULL, reserved_tokens BIGINT NOT NULL,
  INDEX platform_owner_idx(project_id,application_id,subject_id,created_at,run_id),
  FOREIGN KEY(run_id) REFERENCES harness_runs(run_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_commands (
  scope_hash VARCHAR(64) PRIMARY KEY, request_digest VARCHAR(64) NOT NULL,
  run_id VARCHAR(128) NOT NULL, response_json LONGTEXT NOT NULL, accepted_at BIGINT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES harness_runs(run_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_command_audit (
  command_hash VARCHAR(64) PRIMARY KEY, run_id VARCHAR(128) NOT NULL,
  actor_scope VARCHAR(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  operation VARCHAR(40) NOT NULL, reason_text VARCHAR(1000), accepted_at BIGINT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES harness_runs(run_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_evidence (
  evidence_id VARCHAR(128) PRIMARY KEY,
  project_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  run_id VARCHAR(128) NOT NULL, invocation_id VARCHAR(240) NOT NULL,
  invocation_digest VARCHAR(80) NOT NULL, result_json LONGTEXT NOT NULL,
  verifier VARCHAR(128) NOT NULL, verified_at BIGINT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES harness_runs(run_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS platform_slots (
  pool_id VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  slot_index INTEGER NOT NULL, owner_id VARCHAR(128), lease_until BIGINT,
  PRIMARY KEY(pool_id,slot_index)
) ENGINE=InnoDB;
-- An existing other version must be investigated, never overwritten.
INSERT INTO harness_schema(component,schema_version)
SELECT 'platform',1 WHERE NOT EXISTS(SELECT 1 FROM harness_schema WHERE component='platform');
