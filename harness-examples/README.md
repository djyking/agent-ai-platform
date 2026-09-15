# Runnable offline examples

These examples are local development programs. They use scripted model responses, a small in-memory document corpus, and an embedded file H2 database. No model key, MCP service, MySQL instance, or external network call is used. The only write tool inserts a synthetic `demo-feature` record into the selected local database.

There are two entry points plus a small shared CLI:

- `QuestionAnswerExample <data-directory>` runs an Agent that calls the RAG tool, receives a native tool-call result, and answers with a citation. Documents from another project are excluded.
- `ApprovalWorkflowExample <data-directory>` starts a workflow and stops for human input. A `yes` response selects the change branch, which requires a separate exact tool approval. Each command can run in a new JVM using the same directory and run ID.
- `ExamplesMain` also provides inspect, decide, tick, resume, and an explicit automatic synthetic demo.

## Build and run

Run from the repository root. Build and install the reactor once so Maven can resolve the example module's sibling dependencies:

```powershell
.\mvnw.cmd install -DskipTests
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=qa D:/harness-demo'
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=approval D:/harness-demo'
```

The default main class is `io.github.djyking.harness.examples.ExamplesMain`. On macOS/Linux use `./mvnw` and a local directory such as `/tmp/harness-demo`. For a directory containing spaces, put double quotes around the directory inside the `exec.args` value.

The QA output includes a run ID, final answer, citations, and synthetic usage counters. Usage comes from the scripted fixture and does not represent a paid model request. The approval command prints `WAITING_INPUT`, its prompt, and `pending.approvalDigest`.

Copy the returned run ID and digest into the following commands. After the first decision, copy the **new** digest returned for `WAITING_APPROVAL`:

```powershell
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=inspect D:/harness-demo RUN_ID'
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=decide D:/harness-demo RUN_ID HUMAN_DIGEST approve yes'
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=decide D:/harness-demo RUN_ID WRITE_DIGEST approve "approved synthetic change"'
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=inspect D:/harness-demo RUN_ID'
```

Use `reject` in place of `approve` to reject a pending request. Approving the human input with text `no` takes the non-writing workflow branch. A human digest cannot authorize the later tool invocation. An expired, stale, or rejected approval cannot trigger the change. Runs expire after one hour; create a new example run if the deadline has passed.

To run the complete synthetic change without manual copy/paste, the explicitly named demo driver supplies both synthetic decisions:

```powershell
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=demo-approval D:/harness-demo'
```

The resulting `simulatedChanges` should be `1`. Repeating `inspect` or `tick` for the same completed run leaves it at `1`. Every new `qa`, `approval`, or `demo-approval` command intentionally creates a fresh run; use the existing run ID to continue one.

## Persistence and hosting boundary

The directory contains `harness.mv.db`, opened in H2's MySQL compatibility mode. Framework run snapshots, approvals, and events use `JdbcRunStore`. Synthetic changes live in a separate `demo_changes` table. Each change records its run ID and stable, globally scoped invocation ID; matching duplicate receipts cannot insert a second record. No application configuration, external server, or repository source file is changed.

`inspect` opens the existing run and shows its status. Commands operating on an existing run reject a directory without a demo database; a path typo does not initialize a new database. `tick` drives immediately eligible phases. `resume` resumes a paused or recoverable run using the core API, then drives it; it does not bypass approvals or unknown-outcome reconciliation. There is no background worker in this CLI. Start another command after a stop or retry delay.

The actor is a synthetic `demo-user` with broad permissions in `demo-team`. A real host must supply authenticated identity and current authorization, use a protected store, and obtain actual approval decisions. The demo's deterministic model and local receipt table are examples of adapters, not production provider implementations.

## Tests

```powershell
.\mvnw.cmd -pl harness-examples -am test
```

Tests use JUnit temporary directories, reopen the file H2 database between commands, verify source isolation, preserve run budgets and deadlines, reject stale approvals, and assert that the synthetic write occurs once. They do not create persistent demo data in the repository.
