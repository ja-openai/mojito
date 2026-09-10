# Agent translation review: first version

Status: implemented; local validation and rollout are separate from deployment.

## Goal and scope

Agents find issues in existing Mojito translations, save proposals outside TM, and hand them to
one human reviewer through an adapted Review Project. Reuse incident intake and project UI;
add dedicated run, proposal, and feedback records/API inside the existing application.

Independent human review groups, voting, reconciliation, and agent arbitration are a separate
design effort. They are not prerequisites for this version.

## Ownership boundary

Review records own the work and its feedback history. Incidents provide intake, visibility, and
routing; Review Projects provide assignment, the human review UI, and the existing guarded save.
Keep these responsibilities inside Mojito, with its existing database, blob storage, and permissions.

Use three small review-specific record types:

| Record | Owns |
| --- | --- |
| Review run | Review type, scope and input versions, method/configuration, checkpoints, coverage, failures, and completion. |
| Versioned finding/proposal | Stable finding identity across revisions, exact reviewed baseline, optional replacement, rationale/evidence, verification, and current disposition. |
| Review feedback | Append-only human judgments and agent responses tied to the exact proposal revision, with actor, time, explanation, and accepted result when applicable. |

Coverage includes groups reviewed with no findings, using immutable run manifests/checkpoints;
there is no incident for every reviewed string. Optional and context-held proposals can persist
before they are eligible for incident/project routing. Do not infer review coverage from incident
counts or infer proposal acceptance from an incident being closed.

The review API persists a finding before exposing a linked incident to the routing workflow.
The incident links back to the finding; revised proposals keep that identity rather than creating
a new incident for every attempt. Review type/run filters remain available on incidents, while
proposal history and pending feedback are queried through the dedicated review API/MCP operations.
Keep historical incident attribution and original proposal provenance even when a later run responds.

Retain current disposition as ordinary queryable state and append its feedback history in the same
transaction. This is a review-specific history table, not a general workflow or event-replay engine.
Project deletion must not delete runs, proposals, or feedback; project links can become historical.

### Independent issues, shared review context

Keep each finding and linked incident independently resolvable, with its own proposal revisions
and feedback. A shared run does not give incidents a shared status or require closing them together.
Existing human-reported incidents can continue without a run reference.

Use the run reference to group execution history and coverage; feature/locale group keys in its
manifest identify related work. Completing the agent run means its execution finished, not that
all human review has finished. Feedback and follow-up may continue after run completion.
Review Projects independently group eligible work for human assignment and review, so one run can
produce several projects. Neither a run nor a project acts as a parent incident.

Bulk submission is an API convenience: accept one or several findings, with a stable retry key and
result per item. One failed item can be retried without duplicating successful items or losing their
history. The HTTP upload batch does not determine Review Project membership. Project routing uses
eligibility, repository/locale, and assignment/size rules. No incident-group or upload-batch entity
is needed for v0.

## Agent workflow

1. A coordinator freezes source/current-target snapshots and prepares related strings by feature
   or screen, with code references, runtime examples, glossary evidence, and screenshots where useful.
2. Start with one reviewer per locale. Each reviewer checks meaning, grammar, context, and consistency
   together. For a large locale, split disjoint feature groups across more workers; avoid redundant
   reviews of every string by default. Keep composed messages and their dependencies together.
3. A separate verifier challenges each finding, checks whether the original could be valid, and
   validates the proposed replacement. A targeted cross-language/terminology pass can investigate
   specific consistency concerns; cross-language agreement is not proof of correctness.
4. Record completed groups, missing inputs, findings, and unresolved questions. Zero findings is a
   valid result; missing or failed inputs never count as passed review.

Preserve the rubric: obvious mistake, evidenced consistency mistake, debatable improvement.
Only candidates ready for human review enter automatic project creation. Optional improvements
remain filterable; held findings retain the reason they need context. Findings remain candidates
until human judgment; model verification does not constitute native validation.

## Mojito workflow

- Save each finding with exact repository, source ID, locale, reviewed source/target/variant,
  proposed target (optional), explanation, evidence, category, review type, and run reference.
- Reuse the incident queue as the intake surface, with an explicit agent-finding origin. Submission
  and routing never reject a translation or create a current TM variant.
- For v0, identify this review workflow with a stable `reviewType` (for example,
  `TRANSLATION_QUALITY`), distinct from the `reviewRunId` identifying one execution. Persist both
  with its incidents; expose them as incident filters. Review type is separate from issue category,
  severity, and producer identity, so all locale workers in a run use the same workflow type.
- On a committed group checkpoint, create typed incidents and linked Review Projects for findings
  matching `l10n.agent-review.automatic-review-type` (default `TRANSLATION_QUALITY`), with exact
  string/locale identity and readiness after independent verification. Claim, finish, and explicit
  routing calls also retry this handoff; project-creation failure does not discard saved progress.
  A proposed replacement is optional. General incident routing and historical-incident backfill are
  outside v0; existing untyped incidents do not become eligible just because the feature is enabled.
- Batch compatible eligible findings by repository/locale/feature group using existing assignment
  and source-word size rules.
  Creation retries reuse the same project membership and exclude already resolved or routed findings.
  Ambiguous identity stays in triage. Type filtering does not bypass readiness or access checks.
- Keep the historical incident-to-project attribution separate from the newly created review link.
  Freeze started project membership; later findings go into a subsequent batch.
- Use normal priority by default, with explicit escalation for impact. Incident origin remains a
  separate badge/filter. Assignment gaps must stay visible in the PM queue.

## Reviewer experience

Use the existing project shell with a table of source, original-to-proposed diff, short reason, and
decision. Open the existing detailed editor and context panels for complex rows. Reuse Find/Replace
diff and table components where practical.

Actions: accept proposal, edit and accept, keep current, defer. Accept and edit-and-accept immediately
save the human-approved current translation through the existing Review Project save path; normal
exports then consume it. There is no additional publish step. Keep current records a proposal outcome
without altering live text, status, or export inclusion. Defer remains unresolved. A finding without
a replacement supports entering a correction or keeping the current translation.

Record the action and final text. On keep current, offer unnecessary / incorrect / insufficient
context, with an optional explanation prompted for incorrect suggestions. Original-quality labels
are optional except in deliberate evaluation samples. Acceptance alone does not imply original error.

### Feedback back to the agent

Distinguish the human's assessment of the original from their assessment of the suggested fix.
Rejecting a suggestion does not by itself mean that the original is correct or the finding resolved.
Keep both assessments, their explanations, and subsequent responses tied to the proposal revision.

When human feedback requests follow-up, expose it through MCP at start/resume and checkpoints.
An agent response can supply a revised proposal, request missing context, or explain a disagreement
with evidence. Retain the earlier human judgment; an agent response cannot overwrite that judgment
or apply a translation. Consuming feedback records which feedback was answered, separately from
whether the underlying issue was resolved. Repeated reads/submissions must not repeat the response.

Optional improvements stay outside automatic projects. A bad fix for a real issue uses **Request
another proposal**, preserving both assessments for the next agent run. Allow one agent response
per human feedback round; feedback does not start an agent automatically. A challenge or context
request makes **Reconsider finding** available after the human reads the response. Reconsideration
opens an explicit new human round; it never silently changes the earlier judgment or current text.

An agent can revise a never-routed staged finding without human feedback, for example when missing
context becomes available. The old proposal is superseded, with a new immutable revision under the
same finding. Once routed, a revision requires the latest unanswered human follow-up. All follow-up
runs retain the original team and review type.

## Storage and API boundary

- Add a durable proposal record, independent of Review Project deletion. Agent text/evidence and
  reviewed baseline are immutable; an agent revision creates a new proposal linked to its predecessor.
  Retain human outcome, reviewer, timestamp, final text, and applied variant identity separately.
- Add append-only review feedback linked to the exact proposal revision, independent of project
  ownership. Existing `review_project_text_unit_feedback` is mutable, human/project scoped, and
  deleted with a project, so it cannot supply this durable history. Write successful human application
  and its feedback/result linkage atomically through the guarded save transaction.
- Keep `review_project_text_unit_suggestion` as the editable human working copy. It is overwritten
  and cleared on save, so it cannot be the only proposal record. Incident fields also change during
  rejection and cannot substitute for an immutable proposal baseline.
- Add a small run record with scope, method/configuration version, state, completion counts, and
  checkpoint references. Use versioned blobs for inputs, screenshots, evidence, outputs, and handoff
  notes, with durable retention rather than ordinary temporary task-payload retention.
- One coordinator owns a run at a time and orchestrates its subagents. Save per-group checkpoints.
  Sequential takeover uses an expiring claim plus generation/revision checks; an old worker cannot
  publish after takeover. Publish immutable artifacts before atomically advancing the checkpoint.
  Distributed packet scheduling is deferred.
- Checkpoints distinguish `IN_PROGRESS`, `COMPLETED`, `FAILED`, and `MISSING_INPUT`. Save a prepared
  input index before long work and an exact submission-key/payload retry ledger before ingestion.
  Completed coverage remains reusable after the run finishes when frozen inputs, context, method,
  and configuration match. A new run contains only changed, failed, missing, or follow-up work and
  references inherited coverage without counting it as newly reviewed. Deliberately new runs do
  not perform fuzzy cross-run finding deduplication.
- Expose scoped REST/MCP operations for run start/resume/checkpoint, idempotent finding submission,
  pending-feedback retrieval and response, incident-to-project routing, and human resolution. Reuse
  authentication, repository/locale access, task visibility, and translation-write services.
  Agent submission does not grant human approval.

## Using the implementation

- Install the repository's [Codex skill](../../docs/skills/agent-translation-review/SKILL.md) and
  connect to a Mojito backend containing this version. Its [MCP contract](../../docs/skills/agent-translation-review/references/mcp-contract.md)
  documents team discovery, run pagination, coordinator claims, artifacts, checkpoints, per-item
  retries, staged revisions, and feedback. Core REST operations live under `/api/agent-reviews`.
- Running agents requires PM/admin access to the owning team and locale scope. Humans use existing
  Review Project assignment/access checks. All human actions travel through the existing decision
  endpoint with the exact proposal revision/version, review revision, and a stable retry ID.
- Review Projects show an immutable original/proposal diff, concise rationale, verifier notes,
  optional assessments, and feedback history. A current source, context comment, or translation
  change blocks acceptance of the old proposal. Structural integrity checks apply to every accepting
  reviewer, including administrators.
- Attach external evidence as `{label,url}` or same-run uploaded evidence as
  `{label,artifactSha256}` in the proposal's evidence array. Assigned reviewers can read those exact
  referenced artifacts through a project-authorized endpoint; they cannot browse other run artifacts.
  Raster images open inline; other content downloads with safe content-type handling.
- V111 adds the three review tables; V112 adds the historical incident/project links and incident
  filters. Existing Review Projects and untyped incidents retain their workflows. There is no
  Codex transcript database, cross-machine agent scheduler, arbitration service, or voting UI.

## Validation and rollout

The automated checks cover core state transitions, real database transactions and lease races,
idempotent ingestion/routing/decisions, exact feedback lineage, project deletion, authorization,
artifact access, stale edits, and frontend behavior. Local browser checks cover the diff, acceptance,
feedback-only outcomes, stale rows, and narrow layouts. Deployment still requires the matching
backend, schema migrations, frontend, and MCP tools together.

Local validation on 2026-09-09 passed 157 focused backend tests and all 1,227 frontend tests
(101 files, two workers), plus formatting, lint, TypeScript, and the production frontend build.
A disposable MySQL 8.0 instance applied the full Flyway history through V112; all 11 agent review
project integration tests and the existing review transaction suites passed against MySQL.
The agent run mutations use `READ_COMMITTED` so a transaction can read a checkpoint artifact
written by the database blob store's independent transaction. The browser check against that
instance verified acceptance, persisted feedback, stale-acceptance blocking, and keeping newer text.

The frontend build retains existing large-bundle and prototype dynamic-import warnings. An optional
whole-schema Hibernate validation check stops at the existing `application_cache.key_md5`
CHAR/VARCHAR mismatch; Flyway migration and production-style operation with `ddl-auto=none` passed.

A production-quality linguistic pilot remains separate: use human review across locales and sample
unflagged strings before drawing conclusions about accuracy or scaling up automatic review runs.

## Required behavior checks

- Ingestion, checkpointing, project creation, keep current, and defer perform no TM writes.
- Automatic routing selects only the configured review type; unrelated and untyped incidents stay
  outside it. Filtering by run isolates one execution without changing the review-type routing rule.
- Retries create no duplicate findings/projects; resolved feedback survives reruns and project deletion.
- Coverage persists even when a run finds no issues. Human judgments, agent challenges, and revised
  proposals retain their exact revision links; a challenge neither overwrites a human judgment nor
  writes a current translation. Retry acknowledgement is separate from issue resolution.
- Single-item and bulk submission share per-item identity and retry behavior. Resolving one incident
  leaves other incidents in its run/project unchanged; completing a run does not resolve its incidents.
- If source/current translation changes, retain the old evidence and require renewed review before
  applying; do not silently replace the proposal baseline at project creation.
- Retain structurally invalid suggestions and diagnostics as evidence; require the applicable
  integrity checks before application. Acceptance links the exact final text to its proposal.
- A second machine can resume from the committed checkpoint using portable artifacts, without the
  original Codex session. An unsubmitted group may need repeating.
- Pilot rollout includes human review across locales and sampled unflagged strings. Track useful
  findings, unnecessary/incorrect suggestions, missed errors, and review effort. Production prompt
  changes and automatic arbitration are outside this version.

## Related designs

- [Incident intake and MCP](011-webapp-mcp-bad-translation-scaffold.md)
- [Staged suggestions and diffs](020-review-project-find-replace.md)
- [Review identity and concurrency](031-review-project-editing-state.md)
- [Human feedback](029-ai-translation-feedback-evaluation.md)
- [Future evaluation and learning](033-translation-evaluation-and-learning.md)
