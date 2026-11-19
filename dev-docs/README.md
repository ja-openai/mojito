Dev Docs for Codex
==================

This directory holds design docs and working documents used by Codex during implementation. Record key decisions and context here.

Conventions
- Keep this separate from the public site content under `docs/`.
- Design docs live in `dev-docs/design/` and use numeric prefixes for ordering: `001-title.md`, `002-title.md`, ... (pad to 3+ digits).
- Increment the number for each new design doc to keep chronological order without exposing dates.

Tracker
- Use `dev-docs/tracker.md` to capture improvement ideas/tech debt before they become full design docs.
- When an item becomes active, promote it into a numbered design doc.

Generation settings deployment
- Deploy `V129__Generation_Settings.sql` with the matching server. It creates independent
  `generation_settings` and `repository_generation_settings` tables without changing `repository`
  or `repository_aud`, and preserves an existing global exclusion list.
- Before switching development branches, check Flyway history. A migration version must identify
  the same script on every branch; automatic repair does not apply SQL from a conflicting script.
  Databases with branch-specific history need those original migrations reconciled or an isolated
  development database before normal migration can continue.
