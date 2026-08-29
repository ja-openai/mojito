# Webapp MCP Scaffold

Purpose

- Add a remote MCP surface inside `webapp`.
- Keep the transport thin and reusable by layering it over a registry of typed tool handlers.
- Start with a sync-only transport that fits Mojito's current deployment constraints.

What this scaffold includes

- A generic `service.mcp` layer:
  - tool descriptors
  - typed tool handlers
  - a registry
  - a small server facade
- A reusable bad-translation domain layer outside MCP for:
  - translation lookup by string id and observed locale
  - rejection mutation with Mojito audit comments
  - best-effort review-project lineage scoring
  - Slack draft context composition
- A persisted `translation_incident` workflow in `webapp` for:
  - storing lookup/review context before mutation
  - giving admins a review queue in the existing settings UI
  - separating "record incident" from "reject translation"
- Incident MCP tools on top of that workflow:
  - `bad_translation.create_incident`
  - create a persisted incident from `stringId` + observed locale
  - keep repository optional so callers can start from the string alone
  - `bad_translation.reject_incident`
  - `bad_translation.create_and_reject_if_clear`
  - auto-reject only when incident creation resolves to one clear rejectable candidate
- A generic task-inspection workflow on top of existing pollable-task storage:
  - reuse `/api/pollableTasks/{id}` plus the stored task input/output blobs
  - add `GET /api/pollableTasks/{id}/inspection` for a compact debugging view
  - add `task.inspect` so MCP clients can inspect arbitrary Mojito task ids
  - include repository context when input/output payloads expose `repositoryId`, `repositoryName`, or `repository`
  - surface the parsed Mojito error payload and the backend exception headline from the stored stack trace
- An admin-only Review Project decision-integrity investigation tool:
  - `review_project.audit_decision_integrity`
  - require explicit UTC bounds no longer than 48 hours
  - run one Mojito-owned, read-only bounded query and perform carryover/structural analysis in memory
  - return uncapped totals with capped, redacted evidence; never mutate translations or create incidents
  - see `dev-docs/design/027-review-project-decision-integrity-audit.md`
- An admin-only guarded translation-correction operation:
  - `translation.apply_corrections` through MCP and
    `POST /api/admin/translation-corrections/apply` through REST
  - require explicit confirmation and, per row, the Review Project, Review Project text unit,
    repository id and name, locale, TM text unit, current variant, exact old target, and replacement
  - accept target-locale translation Review Projects (`EMERGENCY`, `NORMAL`, and `BUG_FIXES`);
    reject repository source locales, `TERMINOLOGY`, `TERM_CANDIDATE`, and fail-closed `UNKNOWN`
  - lock the current variant first, then lock and re-read the complete audited identity graph before
    comparing every identity and the exact stored old target; normalize only the replacement before
    applying it
  - process at most 1,000 rows in independent transactions and return ordered `APPLIED`, `CONFLICT`,
    or `ERROR` results without echoing old/replacement payloads for skipped rows
  - authorize the MCP mutation before typed argument conversion, then reject the whole request before
    any row transaction if a repository name exceeds 255 characters, a locale exceeds 255 characters,
    an old/replacement target exceeds 1,000,000 characters, or all string fields exceed 8,000,000
    characters in aggregate
  - write through `TMService` without an override path, force `REVIEW_NEEDED`, preserve the prior
    inclusion flag and comment, leave Review Project decision evidence untouched, and never log the
    translation content from the shared variant-write path
  - flush, clear, and immediately re-read the current row before reporting an applied result; return
    the durable ids, stored target, status, and explicit verification checks
- An admin-only exact repeated-current-target discovery tool:
  - `translation.find_repeated_current_targets`
  - scan all current non-source translations, or require both sides to belong to one Review Project
  - use exact same-locale target equality and exact source inequality, with no time or reviewer rule
  - return stable, uncapped keyset pages of candidate members; never infer or mutate a correction
  - see `dev-docs/design/030-repeated-current-target-discovery.md`
- A remote MCP transport at `/api/mcp` that supports:
  - `initialize`
  - `tools/list`
  - `tools/call`
  - `notifications/initialized`
- Authenticated `POST /api/mcp` requests have a non-overridable 32 MiB raw-body ceiling. The
  controller checks declared length and also performs a bounded servlet-stream read before JSON
  parsing, so missing, chunked, or inaccurate `Content-Length` values cannot bypass the limit.
  `l10n.mcp.max-request-bytes` may lower, but never raise, the ceiling. The size preserves the
  existing 20 MiB decoded `image.upload` contract after base64 expansion plus the JSON envelope.
- The transport is Streamable-HTTP compatible in sync mode:
  - `POST /api/mcp` returns `application/json`
  - `GET /api/mcp` returns `405 Method Not Allowed` because streaming is not implemented yet
- The first bad-translation read-only tool can build on top of it by:
  - looking up translation candidates from `stringId`
  - resolving locale mismatches from the observed file/log locale
  - keeping repository optional so callers can start from the string alone

Why this shape

- Mojito already has strong Spring service and admin REST patterns.
- The repo did not have an MCP runtime or JSON-RPC transport to extend.
- This scaffold keeps protocol handling small while making tool implementations easy to add incrementally.

Current limitations

- Sync request/response only.
- No SSE streaming.
- No MCP session lifecycle.
- No resumable event or subscription state.
- Slack stays in draft mode for the incident workflow; send remains a follow-up integration.
- Task inspection is lookup-by-id only. It does not yet provide search/listing for recent failed tasks.
- Review Project decision integrity and repeated-current-target discovery are operator-invoked only;
  no persisted audit history is included.
- Guarded translation correction is synchronous and deliberately bounded to 1,000 independent
  row transactions, 1,000,000 characters per translation field, and 8,000,000 aggregate string
  characters per request. The shared 32 MiB raw MCP ceiling is enforced first, so heavily escaped
  or unusually encoded/non-ASCII JSON can be rejected with HTTP 413 before reaching those looser
  character limits. Callers own higher-level batch lineage, retry selection, and rollback planning.
  An unexpected transaction-boundary failure returns `CORRECTION_OUTCOME_UNKNOWN`, and a lost HTTP
  response can hide a committed result; callers must re-read current state before retrying either
  case.

Task inspection example

- REST: `GET /api/pollableTasks/50255159/inspection`
- MCP: `task.inspect` with arguments `{"taskId": 50255159}`
- Response highlights:
  - `status`
  - `operation` and `taskType`
  - `repository`
  - `error.reportedMessage`, `error.exceptionType`, `error.exceptionMessage`
  - `input`, `output`, `failures`, and related API `links`

Next build steps

1. Keep tools read-only by default unless a clear mutating boundary is needed.
2. Only add streaming/session support if a real client requirement justifies the extra state.
