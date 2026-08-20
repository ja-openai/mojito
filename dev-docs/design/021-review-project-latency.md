# Review Project Latency

## Purpose

This note documents the current latency probes for the review-project detail
page. The immediate goal is to separate save latency, glossary latency, and AI
review latency so we can verify which subsystem is making translators wait.

## Client Behavior

Normal text-unit saves patch the selected text unit into the review-project
detail cache instead of invalidating and refetching the full project detail.
Text-unit saves use a synchronous in-flight guard so repeated shortcuts cannot
start duplicate requests before React updates its pending state. If a stale
variant conflict returns the same user's already-saved decision with matching
translation, status, comment, and notes, the client reconciles that response into
the detail cache; changes saved by another user remain visible as conflicts.
The translation decision draft is owned by the selected review-project text-unit
ID. During a row change, render and save use the new row's snapshot immediately;
they do not wait for the draft-reset effect. This keeps the target, status,
comment, notes, and expected current-variant ID from being combined across rows.
Glossary query invalidation is limited to terminology actions that can change
glossary metadata or resolution state.

Translator saves skip the duplicate frontend integrity preflight. The backend
still enforces integrity checks for translators; if the backend rejects a save,
the frontend runs a follow-up integrity check only to show the detailed failure
message. PM/admin saves keep the existing preflight because those roles can
confirm through integrity failures.

The row-level "Saving" indicator is delayed by 200 ms and covers the save
request, any PM/admin integrity preflight, and the translator rejection
follow-up check that prepares detailed validation errors. Fast saves complete
without flashing a loading state; slow saves still show the indicator.

## Backend Metrics

`ReviewProjectService.saveDecisionDuration` times review-project save decisions
with bounded tags:

- `phase`: `initialRead`, `integrityCheck`, `currentVariantWrite`,
  `decisionWrite`, `decidedCountUpdate`, `detailReload`, or `total`
- `result`: `success`, `failure`, `forbidden`, `conflict`, `bad_request`,
  `error`, or `noop`
- `hasTarget`: `true` or `false`

Phase logs are emitted at `info` when a phase takes at least 250 ms. Total save
logs are emitted at `info` when the whole save takes at least 1 second.

`TextUnitWS.integrityCheckDuration` times the standalone `/api/textunits/check`
endpoint with `result={success|failure|error}`. This is separate from the
backend translator save guard so we can tell whether PM/admin preflight checks
are independently slow.

Glossary match timing is tracked separately by `GlossaryWS.matchDuration`; see
`dev-docs/design/011-glossary-workspace.md`. AI review precompute lookup metrics
are tracked by `AiReviewWS.precomputedReviewLookup`; see
`dev-docs/design/010-ai-observability.md`.

## PromQL

Average save latency by phase:

```promql
sum by (phase) (
  rate(ReviewProjectService_saveDecisionDuration_seconds_sum[$__rate_interval])
)
/
sum by (phase) (
  rate(ReviewProjectService_saveDecisionDuration_seconds_count[$__rate_interval])
)
```

Total save latency by result:

```promql
sum by (result) (
  rate(ReviewProjectService_saveDecisionDuration_seconds_sum{phase="total"}[$__rate_interval])
)
/
sum by (result) (
  rate(ReviewProjectService_saveDecisionDuration_seconds_count{phase="total"}[$__rate_interval])
)
```

Max observed total save latency:

```promql
max by (result, hasTarget) (
  ReviewProjectService_saveDecisionDuration_seconds_max{phase="total"}
)
```

Standalone integrity-check average latency:

```promql
sum by (result) (
  rate(TextUnitWS_integrityCheckDuration_seconds_sum[$__rate_interval])
)
/
sum by (result) (
  rate(TextUnitWS_integrityCheckDuration_seconds_count[$__rate_interval])
)
```

## Reading The Data

If `initialRead` dominates, focus on the initial text-unit, permission,
current-variant, and existing-decision lookups. If `decidedCountUpdate`
dominates, focus on the decided-count update path. If `detailReload` dominates,
focus on the post-save detail fetch. If `currentVariantWrite` dominates, focus
on TM current-variant writes and related flushes. If standalone integrity checks
are slow but backend translator saves are acceptable, the PM/admin preflight
path is the likely source of perceived delay.

## Decision-integrity canary

The optional daily canary delegates to the read-only decision-integrity audit
described in `dev-docs/design/027-review-project-decision-integrity-audit.md`.
The audit performs one bounded query, including 30 seconds of predecessor
context before the requested window, and detects exact-target carryover in Java.
It groups adjacent candidates into runs, retains one-character targets, and uses
redacted, code-point-bounded previews for operator evidence.

The canary uses ordinary static Quartz job and trigger beans at 05:15 UTC. It is
non-concurrent and does not use the dynamic review-automation trigger path
covered by REVIEW-03. Keep it disabled until the V103 MySQL query plan and
latency have been validated, then enable or reschedule it with
`l10n.review-project.decision-integrity-canary.enabled` and `.cron`. The default
lookback is 26 hours and is hard-capped at 48 hours.

Each successful run publishes tag-free gauges for carryover candidate pairs,
carryover runs, deterministic integrity findings, review-needed findings, and
the last completion timestamp. Counters record successful and failed executions,
and a timer records every attempt. These meters are process-local; aggregate
execution counters across instances and use the maximum completion timestamp in
clustered deployments. Disabled instances do not register canary meters.

Summary logs include the bounded window and uncapped counts; detailed carryover
logs use the audit's redacted evidence and are capped at 50 runs. Alert on a
candidate count above zero, a failed-execution counter increase, or an overdue
completion timestamp. Source-equals-target review-needed findings alone remain
informational. The canary never rejects a decision, changes a variant, or writes
translation state; investigation and remediation remain manual.
