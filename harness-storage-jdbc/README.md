# JDBC run storage

This optional module stores runtime snapshots and their append-only execution events in a relational database. It has no Spring dependency. A host supplies a `javax.sql.DataSource` and controls the connection pool, credentials, backup, and retention policy.

The implementation supports H2 2.x in MySQL compatibility mode and is designed for MySQL 8 with InnoDB tables. H2 verification does not establish compatibility with an actual MySQL server; actual MySQL integration is a separate acceptance step.

## Integration

```java
JdbcRunStore store = new JdbcRunStore(hostDataSource);
store.initializeSchema(); // Explicit development bootstrap, no automatic DDL in the constructor.
```

Use an H2 URL such as `jdbc:h2:file:./data/harness;MODE=MySQL` for a persistent local example. H2 without `MODE=MySQL` is rejected because its transaction-scoped timestamp behavior is unsuitable for lease validation after a row-lock wait. H2 tests use isolated in-memory and temporary-file URLs.

For MySQL, the host supplies the MySQL JDBC driver and a configured pool. Production hosts can apply `src/main/resources/db/mysql/V1__harness_run_store.sql` using their migration tool instead of calling `initializeSchema()`. The runtime account needs SELECT/INSERT/UPDATE on the run table, SELECT/INSERT on the event table, and SELECT on the schema table; schema bootstrap additionally needs DDL permissions. All three tables require InnoDB, and existing tables using another engine are rejected. The adapter intentionally rejects databases other than H2/MySQL.

The store does not close the host's DataSource. One SQL schema contains multiple actor/project scopes. Creation keys are hashed together with actor subject and project; reuse with a different creation digest is rejected.

## Consistency rules

- Creating a run and its creation event happens in one transaction. A unique idempotency key makes concurrent creation converge on the same run.
- A worker must own an unexpired lease to commit execution state. Each new lease carries a monotonically increasing fencing token.
- Lease validity is determined using the database clock, so a worker with a skewed wall clock cannot keep an expired lease alive.
- A successful state transition and its execution event are committed together. Failed transitions append no event.
- State updates compare the revision observed by the caller. Pause, cancellation, approval, and other external changes cannot be silently overwritten by an older worker snapshot.
- An external control update preserves the current fencing token and lease, so it does not immediately start a second worker while the first is still inside a remote call. The runtime reconciles the updated revision and control flags before committing the result.
- Identity, definition, tool contracts, budget limits, creation time, and deadline are frozen. Execution counters cannot decrease. A token reservation may be released only while committing the matching known model response, using the exact reservation-to-actual-usage formula; external control updates cannot release it.
- A snapshot persists tool invocation identity and intent. Storage itself never re-executes a tool or infers that an interrupted write is safe to retry.
- SQL schema v1, snapshot schema v1, and runtime state version `1` are accepted. An unknown version fails explicitly and requires a host-directed migration.

## Verification scope

The module tests exercise concurrent run creation and worker claims, stale fencing tokens, lease expiry (including contended row locks), optimistic conflicts, transaction rollback, file persistence after all connections close, and serialization of interrupted tool intents. `HarnessSqlRecoveryTest` runs the real runtime through model reservation settlement, approval across store reconstruction, successful receipt persistence, and uncertain writes that remain reconcilable after cancellation. Tests use independent H2 databases and never connect to an existing business database.

Run `./mvnw -pl harness-storage-jdbc -am test` (Windows: `mvnw.cmd`). Database clock tests also verify session timezone independence and that time advances within a transaction after a prior statement.

No background lease renewal or distributed worker service is provided. The runtime chooses a lease that bounds its synchronous call and must not commit after expiry. Fencing protects this SQL ledger; remote side effects still require an idempotency/reconciliation contract at the tool boundary.

Snapshots and events may contain application data. Supply already-redacted event metadata, restrict database access, and configure retention at the host boundary. Credentials belong in a secret provider, not persisted run state.
