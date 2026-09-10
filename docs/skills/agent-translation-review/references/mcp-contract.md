# Mojito agent review MCP contract

These names are registered by Mojito's `service.mcp.agentreview` handlers. Connect to the intended
Mojito instance using its existing authentication. All operations below require a PM or admin;
the backend also checks team and locale access. Human review uses the assigned Review Project UI.
Use the deployed tools' input schemas if a server version differs from this document.

## Discover and prepare

| Tool | Arguments | Result |
| --- | --- | --- |
| `agent_review.list_teams` | Optional `afterId`, `limit` | Enabled managed teams as `teams: [{id,name}]`, `nextAfterId` |
| `agent_review.list_runs` | `teamId`, optional `beforeId`, `limit` | Accessible run summaries as `runs` in descending ID order, `nextBeforeId` |
| `agent_review.inspect_run` | `runId` | Run state, scope/version metadata, claim and checkpoint |
| `agent_review.create_run` | Creation fields below | New or idempotently reused run |
| `agent_review.claim_run` | `runId`, `owner`, `expectedGeneration`, optional `leaseSeconds` | `{run,routing}` |

Select the requested team. A single accessible team is an unambiguous default; when several teams
could own the work, obtain that choice when configuring the first run. Do not guess team IDs.
`text_unit.search` can resolve repository/locale IDs and retrieve text-unit/current-variant IDs,
source/comment, target/status/inclusion, and source context. Page through the requested scope;
its result `matches.length` is not a total. Use exact stored strings when capturing baselines.

For `list_runs`, omit `beforeId` on the first page, then pass `nextBeforeId` until it is null.
The cursor refers to the last scanned run, including runs outside the caller's locale access.
An empty `runs` page can therefore still have a cursor; continue paging. Completed runs and their
completed groups remain reusable when scope, frozen inputs/context, method, and configuration match.
Only `RUNNING` runs accept a claim. Start a narrow new run for remaining work after completion and
retain references to earlier coverage instead of submitting those findings again.
List entries contain run metadata, coverage counts, and manifest/checkpoint references, without the
decoded `checkpoint`. Call `inspect_run` for a selected run's full per-group checkpoint.

Map each `text_unit.search` match to a proposal baseline as follows:

| Search result | Proposal field |
| --- | --- |
| `tmTextUnitId` | `tmTextUnitId` |
| `source`, `comment` | `source`, `sourceComment` |
| `tmTextUnitVariantId` | `baselineVariantId` |
| `target`, `status`, `includedInLocalizedFile` | `baselineTarget`, `baselineStatus`, `baselineIncludedInLocalizedFile` |

`tmTextUnitCurrentVariantId` identifies a separate current-pointer record; it is not the baseline
variant ID. If `tmTextUnitVariantId` is null, set all four baseline fields to null, even when search
returns `includedInLocalizedFile: false`. Preserve a real variant's empty target as `""`.

Create a run with:

- `requestKey`: stable retry key, up to 128 characters. Creation idempotency is scoped to the
  authenticated creator; another person should resume by shared run ID.
- `reviewType`: `TRANSLATION_QUALITY` for this workflow (uppercase identifier, at most 64 characters).
- `teamId`: chosen owning team.
- `methodVersion`, `configurationVersion`: version strings, each at most 128 characters.
- `groups`: 1–200 entries, at most 10,000 total string/locale pairs. Each entry has `key`,
  `repositoryId`, `localeId`, `featureGroup`, `tmTextUnitIds`, and `inputFingerprint`. Keys and
  fingerprints are at most 128 characters; feature names at most 255. The same repository,
  locale, and text-unit combination may appear only once in a run.
- `inputManifestJson`: a JSON **string** describing frozen inputs and context provenance; at most
  2,097,152 characters. The entire stored manifest, including groups and the JSON-string envelope,
  must also fit 2 MiB of UTF-8 bytes. Keep this manifest compact; store larger prepared input bundles
  as artifacts immediately after claiming the run.
- Optional project defaults: `dueDateOffsetDays` (1–365, default 7), `maxWordCountPerProject`
  (1–100,000, default 1,500), `assignTranslator` (default true).

The run returns `manifestSha256`, `inputFingerprint`, `revision`, `claimGeneration`,
`leaseExpiresAt`, `checkpoint`, coverage counts, and human-project settings. To resume from another
machine, read the manifest artifact and the artifact indexes linked by each group checkpoint.

Claim with an opaque coordinator/session identifier. Use generation `0` for a new unclaimed run
or the generation returned by inspection. The default lease is 300 seconds; allowed range is
30–1,800 seconds. Renew using the same owner and current generation before expiry. After an
expired lease, takeover increments the generation. A new person/session must not impersonate
the old owner; use a new identifier and wait for an active lease to expire.

Every write below carries `claim: {"owner":"coordinator-id","generation":1}`. Use the actual
returned generation. The authenticated user, owner, generation, and lease must all match.

## Artifacts and coverage

| Tool | Arguments | Result |
| --- | --- | --- |
| `agent_review.upload_artifact` | `runId`, `artifact: {claim,contentType,contentBase64}` | `{sha256,contentType,byteCount}` |
| `agent_review.read_artifact` | `runId`, `sha256` | `{sha256,contentType,contentBase64,byteCount}` |
| `agent_review.checkpoint` | `runId`, `checkpoint` object below | `{run,routing}` |
| `agent_review.finish_run` | `runId`, `completion: {claim,expectedRevision,cancel}` | `{run,routing}` |
| `agent_review.route_run` | `runId` | Routing summary; safe to retry |

Artifacts contain at most 2 MiB of decoded bytes; content type is at most 128 characters. Split
larger bundles and save an index artifact. The returned `sha256` identifies the stored content-type
and base64 envelope, not just the decoded file bytes. Keep this server-returned reference; the
backend checks its integrity on every read. Artifacts use permanent storage scoped to their run.

Before starting a long group review, upload its frozen rows and context, then a JSON index linking
those digests. Commit an `IN_PROGRESS` checkpoint pointing to that index so another coordinator can
find the inputs after a crash. Each later checkpoint index must retain input references as well as
new worker/verifier results and handoff notes. Keep referenced artifact run IDs when referencing
artifacts from an earlier run; hashes alone do not move content between run namespaces.

Before `submit_proposals`, also save an immutable retry ledger containing every intended proposal's
submission key and exact payload, and commit an `IN_PROGRESS` checkpoint linking it. This makes an
uncertain batch recoverable on another machine. On resume, page `list_proposals` and reconcile saved
keys against that ledger before repeating work. A successful proposal may exist even when the last
group checkpoint still says `IN_PROGRESS`. Replace only the claim when replaying a saved payload.

Checkpoint shape:

```json
{
  "runId": 123,
  "checkpoint": {
    "claim": {"owner": "coordinator-id", "generation": 1},
    "expectedRevision": 0,
    "groupKey": "fr/settings",
    "status": "IN_PROGRESS",
    "reviewedItemCount": 0,
    "artifactSha256": "<digest returned by upload_artifact>",
    "note": "Inputs saved; review has not started."
  }
}
```

Statuses are `IN_PROGRESS`, `COMPLETED`, `FAILED`, `MISSING_INPUT`. Completed groups must account
for every scoped string, including groups with no issues. Failed/missing-input checkpoints require
a note. A completed group is immutable; changed inputs need a new run. In-progress groups remain
resumable and do not count as failed. Finish requires all groups to be terminal unless `cancel` is
true. Record incomplete work honestly instead of completing a group to unblock finish.

Checkpoint and finish use the latest run `revision`, which is separate from the claim generation.
After a lost response, inspect first. An exact retry of the latest checkpoint is acknowledged;
if another checkpoint advanced the run, reconcile from its saved state instead of blindly replacing
`expectedRevision`. Keep proposal retry keys and payloads unchanged after takeover, replacing only
the claim. Claim fields are excluded from proposal/checkpoint idempotency fingerprints.

Routing runs after successful claim/checkpoint/finish and can also be retried explicitly. The
`routing` object has `runId`, `projectIds`, `proposalCount`, `skippedCount`, and `errors`. Check
`errors` even when the tool succeeded: committed review progress survives a routing failure.

## Findings, revisions, and feedback

| Tool | Arguments | Result |
| --- | --- | --- |
| `agent_review.submit_proposals` | `runId`, `proposals: [...]` | Ordered per-item `results` |
| `agent_review.list_proposals` | `runId`, optional `afterId`, `limit` | `proposals`, `nextAfterId` |
| `agent_review.proposal_history` | `proposalId`, optional `afterId`, `limit` | Revisions of its finding as `proposals`, `nextAfterId` |
| `agent_review.pending_feedback` | Original `runId`, optional `afterId`, `limit` | Pending human `feedback`, `nextAfterId` |
| `agent_review.feedback_history` | Exact `proposalId`, optional `afterId`, `limit` | Preserved human/agent `feedback`, `nextAfterId` |
| `agent_review.respond_to_feedback` | Responding `runId`, `response` object below | Saved agent feedback entry |

These paginated operations default to 50 rows, with a maximum of 200. Use the returned `nextAfterId`
until null. A full last page can be followed by an empty page. Pending feedback may arrive later;
start a fresh scan from zero at the next review boundary.

Each submitted proposal includes:

- `claim`, `submissionKey` (stable within the run, at most 128 characters), `groupKey`, `tmTextUnitId`.
- Exact `source`, `sourceComment`, `baselineVariantId`, `baselineTarget`, `baselineStatus`, and
  `baselineIncludedInLocalizedFile`. The baseline variant must belong to the scoped string/locale.
  When no variant exists, all four baseline fields are null. Empty text is not a missing variant.
- `proposedTarget`, which can be null when a human needs to supply a fix.
- `category`: `OBVIOUS_ERROR`, `CONSISTENCY_ERROR`, or `OPTIONAL_IMPROVEMENT`.
- `readiness`: `READY`, `HOLD`, or `OPTIONAL`. Unverified/context-held findings use `HOLD`.
  Optional improvements cannot be `READY` and stay outside automatic projects.
- `rationale`, optional `evidenceJson` (JSON string; linked-evidence shape below), and `producerIdentity`.
- For `READY`, distinct `verifierIdentity` and `verificationRationale` are required. Identity should
  distinguish the actual workers, for example `gpt-6/fr-reviewer` and `gpt-6/fr-verifier`; merely
  relabelling one agent's output does not satisfy the workflow's independent verification.
- Optional `integrityDiagnostics` to retain known structural problems.
- For a revision, `previousProposalId` identifying the exact previous proposal. A never-routed
  `OPEN` draft (`HOLD`, `OPTIONAL`, or `READY`) accepts `respondsToFeedbackId: null` or its omission.
  Once routed, the prior proposal must await follow-up and `respondsToFeedbackId` must identify its
  latest pending human feedback round. Keep the same team, review type, repository, string, and locale.
  Use a new submission key; the new immutable proposal retains finding/incident identity, increments
  `proposalRevision`, and marks the previous proposal `SUPERSEDED`. A resolved or superseded proposal
  cannot be revised.

Source, target, rationale, diagnostics, and evidence fields are limited to 64,000 characters each;
identity labels to 255. Submit 1–50 proposals per call. The general MCP body ceiling is 32 MiB, so
split unusually long or heavily escaped text into smaller batches.

### Evidence humans can open

Encode `evidenceJson` as an array of labeled links or an object with an `items` array. For example,
the decoded JSON can be:

```json
[
  {"label": "Glossary entry", "url": "https://example.com/glossary/term"},
  {"label": "Settings screenshot", "artifactSha256": "<digest returned by upload_artifact>"}
]
```

Pass this value as a JSON **string**, for example
`"evidenceJson": "[{\"label\":\"Glossary entry\",\"url\":\"https://example.com/glossary/term\"}]"`.
External links support HTTP/HTTPS URLs without embedded credentials. Artifact links use the exact
server-returned digest and must already exist in the same run as the proposal revision. When a new
responding run reuses earlier evidence, read that artifact from its original run and upload it to the
responding run before linking its returned digest. A cross-run reference in the checkpoint alone
does not expose that artifact to the assigned reviewer. The project panel shows up to 30 entries.

After routing, Mojito turns an `artifactSha256` entry into
`/api/agent-reviews/projects/{projectId}/proposals/{proposalId}/artifacts/{sha256}`. This endpoint
checks project access and the exact proposal evidence link. PNG, JPEG, WebP, and GIF images can be
viewed inline; other files download. Coordinators still use `agent_review.read_artifact` with the
run ID to restore arbitrary permitted run artifacts. Do not invent project IDs or place a manager-only
raw-run artifact URL in reviewer evidence.

### Submission results

Each result includes `index`, `submissionKey`, and either `proposalId`, `findingId`,
`proposalRevision` or `errorCode`, `errorMessage`. A batch can partly succeed. Same key with changed
content is a conflict; a new proposal revision requires a new key. After an uncertain item result,
use the same key and payload to reconcile/retry instead of duplicating the finding.

### Human feedback

For feedback without a new fix, submit `response: {claim,requestKey,feedbackId,action,actorIdentity,
explanation,evidenceJson}`. Allowed actions are `CHALLENGE` and `CONTEXT_REQUEST`; use proposal
submission for `REVISED_PROPOSAL`. One human feedback round accepts one agent response. An already
resolved/superseded round cannot be revived by an agent. The response acknowledges the round; it
does not resolve the original issue or override a human judgment.

Human feedback remains queryable after the original run completes. Start or reuse an active
responding run with the original team and review type, containing the same repository/string/locale,
then reference the earlier proposal/feedback. Use an unfinished responding group so its retry ledger
and results can be checkpointed. After a challenge or context request, the human can explicitly
reconsider the finding and provide a new judgment or feedback round; the earlier judgment stays in
history. Wait for that new round before replying again.
Read `proposal_history` and `feedback_history` when needed to understand earlier judgments.
After takeover by another person, check that history before replaying an uncertain response:
feedback retry acknowledgments retain the original actor, while already saved responses remain
visible to the new coordinator.

Whole-tool lease, revision, or retry-content conflicts return MCP errors with
`code: AGENT_REVIEW_CONFLICT` and `details.status: 409`. Invalid requests, missing records, and
forbidden scope can also produce MCP errors; input-conversion and access denials may omit a structured
agent-review code. Within `submit_proposals`, per-item failures
instead use `errorCode` values such as `409 CONFLICT`, or `INTERNAL_ERROR` for an uncertain internal
failure. Inspect every item. Stop and reconcile conflicts; do not convert them into new keys or
silently discard human feedback.
