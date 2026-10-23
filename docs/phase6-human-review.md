# Human review of generated answers

New Studio drafts with human review enabled compile to answer generation, a Human review checkpoint, and End using the original answer. The reviewer sees the generated answer and provides an opinion; approval never substitutes the opinion for the answer or invokes the model again. Rejection stops the Run through the existing decision mechanism. Evaluation Runs retain this real checkpoint and require an assigned human decision before publishing.

## Explicit compatibility boundary

`Human.reviewContext` is optional and defaults to false. False is omitted from serialization, preserving legacy workflow fingerprints, pending action contracts, and frozen releases. The existing three-argument Java constructor is retained. Existing Human input steps keep their prior behavior. The new Studio compiler opts in and declares only `answer` in the immutable release's `reviewFields`.

## Projection and authorization

An opted-in Human checkpoint is checked against the immutable release, workflow program and model, checkpoint schema and fingerprint, program counter, transition count, node/prompt/contract digest, pending action identifier, and approval invocation/digest before exposing any context. Invalid checkpoints fail closed.

Only named release fields from checkpoint variables may be projected. Names must be distinct and bounded; missing, null, unavailable, or oversized fields are masked, and `reviewComplete` is false. Each field is limited to 16,384 characters and UTF-8 bytes, with 65,536 bytes total and at most 64 fields. Content is never silently truncated into an approvable review. Undeclared variables and the rest of the workflow memory are not exposed.

The caller needs the assigned approval identity and current approval grants. Protected Studio knowledge also requires that reviewer's current knowledge membership and collection/source access. The Run owner's delegation is never borrowed to disclose content to a reviewer. Public output follows its explicit release policy.

The approval ETag includes the projected body, so a source ACL change invalidates a previously visible approval even when the Run revision has not changed. The command rechecks local Studio source access while holding the project lock shared with source mutations. External policy I/O happens before the transaction; changed Run revisions are rejected. No external policy request is made while holding the SQL command transaction.

The decision accepts its existing digest, ETag, input, and reason contract. Client-supplied replacement fields are rejected. Exact same-key receipts remain replayable after the original approval has completed without executing it again.

## Verification

`StudioHumanReviewTest` covers generated-answer visibility, unchanged final answer and model-call count, forged replacement fields, exact receipt replay after completion, reviewer-specific private knowledge grants, revocation with an unchanged Run revision, malformed checkpoint rejection, field limits and undeclared-variable exclusion, and legacy serialized JSON/fingerprint compatibility.
