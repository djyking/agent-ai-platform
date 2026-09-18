# Repository conventions

- Development branches use the `develop/` prefix, as explicitly requested by the owner on 2026-09-18. Do not create `codex/` branches.
- Preserve existing commits when renaming branches. Verify replacement references before deleting old names; do not implicitly merge into `main`.
- Keep credentials and private deployment files out of Git. Local acceptance data and credentials belong under ignored `.work/`.
- Core execution remains plain Java 17. Browser clients use the platform API through a server session and never receive application credentials.
- Stage three scope and validation are recorded in `docs/phase3-implementation.md`. Stage four is a separate task.
