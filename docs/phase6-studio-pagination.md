# Studio task pagination

Phase six removes the project-wide 2,000-document failure from the Studio task path. Both the task workspace and the application's feedback view use bounded server-side pagination. Application and owner filters run in SQL before documents or Run payloads are loaded.

## HTTP contract

`GET /v1/projects/{project}/studio/tasks?applicationId={optional-id}&limit=50&cursor={optional-token}`

- `limit` defaults to 50 and accepts integers from 1 through 100. The console requests 20.
- Unknown or duplicated parameters, empty parameters, invalid application identifiers, malformed cursors, and cursors for another user/application/project/permission scope return 400.
- The existing `items` array is preserved. `nextCursor` is null only at the end; `hasMore` makes continuation explicit. `pageCursor` re-reads the current page within the same snapshot, including when navigating back to page one.
- Consumers must follow `nextCursor` until null. Older clients still understand the item shape, but must adopt pagination to enumerate all history; the endpoint no longer promises an unbounded array.
- Records sort by creation milliseconds descending and canonical task UUID descending. Equal timestamps have deterministic ordering. The signed cursor expires one hour after the first page, and continuations do not extend that deadline.
- The first page captures a sequence watermark while briefly holding the same project lock used by Studio task creation. The lock is released before loading results or checking output permissions. Later inserts are excluded even when their timestamps tie or precede the last page. Refresh starts a new snapshot.
- A cursor is not an authorization grant. Every request authenticates the current principal. Run access and knowledge/output permissions are checked for each returned item; revoked sources, permissions and archived payloads remain hidden. A changed permission scope invalidates the cursor; refreshing obtains a newly scoped page.

## Storage and migration

Studio schema changes from version 1 to 2. `studio_task_index` stores immutable project, owner assignment, application, task, snapshot and creation-time dimensions plus a monotonically allocated sequence. Two compound indexes cover project/owner ordering, with and without an application filter.

Task documents, their index records and the underlying Run are written in the same existing transaction. Rollback removes both records; updates cannot change ownership or ordering dimensions. Reviewed retention preserves task identity and ordering while removing payloads, so archive processing does not invalidate pagination.

The existing explicit offline migration entry point invokes Studio initialization. It creates indexes and backfills version-one TASK documents in batches of 500 without changing the JSON or Run state. Migration is retryable after MySQL's implicit DDL commits. Only after successful index and completeness validation is the schema version changed. Read-only runtime startup requires version 2 and the exact compound indexes; it never creates or repairs tables. Old version-one binaries reject version 2, so deployment must drain/isolate old processes before migration.

Candidate selection joins task and snapshot documents in one bounded query and reads at most `limit + 1` rows. Dynamic Run projections are evaluated only for the returned page, not the project's complete task history. Application/template lists retain their existing separate limits; this migration specifically addresses the unbounded, continuously growing task ledger.

## Verification

- H2 integration tests create more than 4,000 task documents, including 2,106 tasks belonging to the tested user, and enumerate all pages without duplicates or omissions.
- Concurrent commits between pages include both same-time lower-UUID tasks and newer tasks; neither enters the captured snapshot.
- Tests cover user/client/project/application/permission cursor isolation, invalid sizes/parameters, tampering, expiry, legacy migration, missing-index rejection, transaction rollback, immutable ownership, archived task payloads and knowledge revocation while reusing a cursor.
- Browser-component tests cover page navigation, stable previous-page cursors, refresh, server-side application filtering, and aborted responses from a previous actor/application scope.

Local integration and MySQL acceptance results are recorded by the phase-six delivery report after the shared build and local end-to-end run. These tests do not constitute the separate 24-hour or Linux seven-day observation.
