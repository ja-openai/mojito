# Agent translation review: first version

Status: core, incident-based manual/scheduled batches, and explicit human re-review implemented.
Local validation and rollout are separate from deployment.

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

The incident queue also supports a searchable locale filter, persisted as `locale` in the URL.
It matches the displayed locale exactly (case-insensitively): resolved locale when available,
otherwise observed locale. The server applies it alongside the other filters before pagination,
so the count and results cover the full matching scope. Selecting another locale clears the
previous incident selection; direct links may include both `locale` and `incidentId`.

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
  `routingPolicy=QUEUED` records the incidents for later manual or scheduled batch creation;
  omitted policy retains `IMMEDIATE` routing. A proposed replacement is optional. Untyped incidents
  are selected only through the explicit incident-source creation path below.
- Batch compatible eligible findings by repository/locale/feature group using existing assignment
  and source-word size rules.
  Creation retries reuse the same project membership and exclude already resolved or routed findings.
  Ambiguous identity stays in triage. Type filtering does not bypass readiness or access checks.
- Keep the historical incident-to-project attribution separate from the newly created review link.
  Freeze started project membership; later findings go into a subsequent batch.
- Use normal priority by default, with explicit escalation for impact. Incident origin remains a
  separate badge/filter. Assignment gaps must stay visible in the PM queue.

## Incident-based batch creation

The primary entry points are **Create review project** and **Review automation configuration**.
Both share eligibility and routing in `IncidentReviewBatchService`; manual planning uses
`ManualIncidentReviewService`. Manual HTTP endpoints are
`POST /api/incident-review-projects/preview` and `POST /api/incident-review-projects`.

- Select **Incidents** as the source. The common mode is **All eligible incidents** of the selected
  type and owning team, regardless of repository. Repository/review-feature scope is an optional
  narrowing filter. Manual creation requires at least one selected locale, matching current-translation
  creation; use **Select all** to include all available locales. Empty locale selection disables
  preview and creation. Automation's empty locale include-list still means all eligible locales.
  A batch can create several projects while preserving repository, locale, run and finding attribution.
  Keep translator assignment, due date, and maximum source words as assignment controls. Incident
  review type is distinct from the project's Normal/Emergency priority. The HTTP request explicitly
  sets `allRepositories=true`; omitted scope never silently widens a legacy request.
- Manual creation previews eligible incident/string counts and resulting locale batches before
  creation. Automation saves the same selection policy and executes it through **Run now** or the
  configured schedule. Reuse one eligibility and batch-creation operation for both paths.
- Select open incidents with exact string/locale identity and a reviewable finding. Reuse existing
  proposal evidence and revisions. Ordinary incidents need an explicit conversion to a staged
  review record; preserve their identity and historical attribution, and label human-requested
  review truthfully rather than inventing independent agent verification. A replacement is optional.
- Group compatible work by repository/locale and apply the existing team and source-word limits.
  Preserve each incident's independent decision and feedback history. Multiple incidents for the
  same string/locale must not silently lose findings when project membership deduplicates rows.
- Producers using batch creation set `routingPolicy=QUEUED` when creating the run. Checkpoints
  record incidents without assigning projects; `IMMEDIATE` remains the compatibility default.
- Repeated or overlapping creation must reuse pending review work and cannot create duplicate
  project membership. An already handled unchanged finding stays handled after project closure.
  **In review project** continues to mean active assignment to an open project; do not extend it to
  closed projects as a proxy for review history. Use explicit human decisions on the exact reviewed
  state for repeat prevention, and **Pending** to reopen a review in the same project. Closing a
  project alone is not evidence that a string was reviewed or accepted.
- New acceptance and keep-current decisions record the exact source, context comment, current
  variant, target, status, and export inclusion observed under the save locks. The same state is
  suppressed within the owning team and review type on fresh submission and batch intake. Legacy
  decisions have no trustworthy receipt and are not backfilled from an old proposal baseline.
- Project creation stages the review and never changes current translation text, status, or export
  inclusion. Only the existing guarded acceptance action applies a proposal.

### Bounded intake and growing history

- Manual preview starts a fresh finite selection with the current upper incident ID; it never
  reads or advances an automation cursor. Internal reads examine at most 500 incident IDs at a
  time and release their entity state after each page. Preview continues across skipped and
  outside-scope pages until the full selection is checked or an explicit eligible-incident limit
  is reached. Without an overall limit, **All incident types** includes the eligible
  translation-quality incidents from a quality-only selection, even after an older scheduled
  sweep. Eligibility can still change between preview requests.
- **Maximum incidents overall** is optional; blank includes every eligible incident in the selected
  repository/feature, locale, team and type scope. A supplied positive `maxIncidentCount` selects
  the first eligible incidents by ID, rather than limiting scanned IDs. `limitReached` indicates
  that at least one additional eligible incident was found. Preview's eligible count is the chosen
  total, not a hidden first page or a claim about the remainder of a limited selection.
- Manual incident projects use the optional maximum source words per project, like regular
  manual projects; one string is never split. There is no per-project incident-count limit.
  Compatible repository/locale/run groups and duplicate string waves are carried across read
  pages, so the internal page size does not change project membership. Blank word limit means
  no size-based splitting. Previously saved job inputs remain readable; their removed
  `maxIncidentsPerProject` setting is ignored.
- Manual Preview and Create use the existing Quartz pollable-task mechanism. Both return HTTP
  202 with a task ID before scanning; the client polls task progress and stored output. Preview
  is optional. Create plans the current finite selection and processes every planned project in
  the background, using one atomic transaction per project. Creation loads and locks that
  project's rows together; transaction size scales with the project, while scanning remains
  paged. Navigating away does not cancel server work; the task URL can reconnect to it without
  submitting another creation job.
- Preview remains read-only and returns `incidentBatches` for the displayed counts. Create makes
  its own fresh plan, so eligibility and counts can change after a preview. It rechecks access,
  scope, exact current state, assignment eligibility and word/group boundaries under the existing
  lock order for every project. Changed or handled incidents can be skipped; creation does not
  add incidents beyond the job's finite plan.
- Both jobs run as the requesting user and recheck current authorization. Task status, input,
  output and inspection are readable only by that owner while they retain PM/admin access.
  Scheduled automation keeps its existing independent behavior.
- Creation publishes progress and saves aggregate results after each committed project. A failed
  task retains those saved project links; a new creation request safely excludes active
  assignments. Output persistence follows the database commit, so a failure between those steps
  can leave a committed project absent from the saved report. This is not an automatic retry or
  crash-recovery ledger; inspect the project list before starting a replacement task.
- `scannedIncidentCount` counts examined IDs, including skips and rows outside the selection;
  outside-scope IDs and contents are never returned. Manual preview counts cover all examined
  pages, with at most 100 skipped details retained. Exclusion summaries show capped examples and
  distinguish sampled reasons from the complete skipped count.
- Scheduled automation retains the durable cursor for the owning team and canonical
  repository/locale/type selection. Each scheduled slice examines at most 500 IDs and creates at
  most 25 projects, with `hasMore` indicating another slice. Assignment, name, deadline and word
  limits do not reset its progress. Creation commits progress and assignment together; a fixed
  upper ID makes the sweep finite. A completed sweep wraps for later arrivals and newly eligible
  incidents. Same-scope callers serialize on the cursor; overlapping scopes lock and recheck the
  underlying finding before assigning it.
- Selection seeks by incident ID using open-incident indexes, then bulk-loads a bounded set of
  current translations, proposals and assignment state. Linked incidents remain candidates so
  closed, undecided assignments can be reconsidered; open assignments are skipped after inspection.
  Checkpoint artifacts are read once per run in a slice. MySQL seeks explicitly use the V124
  status/ID indexes; quality and legacy-null types use separate limited ranges merged by ID.
  Validate those query plans on the production distribution during release.
  Retain resolved incidents, immutable revisions and feedback as history; they do not need to be
  deleted to keep review batching bounded.
- Historical agent findings and incidents may remain stored. Ordinary immediate routing and
  manual/scheduled batches only include a finding whose source, context, current variant, target,
  status and export inclusion still match. Recheck that snapshot under the string/current locks
  before creating review work, even when the new current translation has no human-review receipt.
  A historical report does not mark its old variant as approved. Reviewing any variant or past
  incidents would require a separate explicit historical scope; this is not currently exposed.
- Both generic incident intake and agent submissions use indexed, unique active finding keys:
  exact translation state, owning team, review type and a stable optional `concernKey`. Without
  `concernKey`, normalized reason/rationale identifies the concern. Repeated pending findings reuse
  their identity; different concerns remain separate. Cross-run submission aliases retain exact
  retry behavior after the reused finding resolves. Closing/superseding releases the active key
  while keeping the historical key and records.
- Generic incident creation accepts optional `teamId`, `reviewType` and `concernKey`. A report with
  no team is unscoped: it cannot be suppressed by another team's human decision. New keyed records
  support indexed reuse; legacy matching considers only the most recent 100 open incidents or 100
  proposals per pending disposition for the same string/locale, with exact state/scope/reason
  comparisons. It never guesses an explicit concern key
  from old prose, scans all historical findings, or invents reviewed-state receipts for old decisions.
- Human acceptance/keep-current receipts are queried by indexed state fingerprint and the owning
  team/type, in bounded groups. **Pending** deliberately creates a linked round despite that
  receipt. This is persisted repeat prevention, not model training or fuzzy semantic deduplication.

Acceptance checks for this increment include matching manual/scheduled selection, multi-incident
batching and word limits, truthful conversion of ordinary incidents, overlapping-run retries,
already reviewed unchanged findings after project closure, explicit re-review, and no TM writes
during creation. Keep this work separate from independent human review groups or reconciliation.

## Reviewer experience

Interaction, clarified with PM feedback on September 14: review one incident at a time in the
existing project shell. Batch creation and assignment do not imply table-based editing or decisions.

- Reuse the normal three-panel presentation: the existing source/translation navigation list on
  the left, translation editor and review chat in the center, and source, comment, string ID,
  glossary, placeholders, history and context on the right. Add an admin-only **Report** tab for
  the reported finding, evidence, optional assessments, and incident feedback history. Keep the
  normal panel widths and controls. Review one string at a time.
- Filter incident work by review status: **Awaiting review** includes **Changed since review**;
  **Reviewed** excludes requests waiting for an agent. Use the normal filter icon; translation
  status and Edited filters remain specific to regular projects. Filtering preserves unsaved work.
- Initialize the editor from the live current translation. Keep a compact **Reported issue** strip
  above the editor and context panel, showing the frozen original, optional proposed correction,
  and finding. The version matching the editor shows **✓ Selected** as a plain status label;
  the other version shows **Use original** or **Use suggestion**, which copies into the editor without saving. Selection follows
  edits and Reset; when custom text matches neither version, neither is selected. If both versions
  are identical, show only the original as selected. Keep both snapshots visible after Use or Accept.
  Admins see **View report →**, which opens the full Report tab. Other reviewers retain the strip
  and ordinary editing controls, without the link or Report tab. Later AI chat suggestions remain
  separate from the recorded finding, with an explicit **Review** action and no automatic review
  on incident load.
- Keep the ordinary **Reset** and **Accept** controls. Accepting unchanged current text records
  `KEEP_CURRENT` without changing live text, status or export inclusion. Accepting an edit or used
  suggestion applies it through the existing guarded save. Preserve exact proposal provenance,
  source/current-version checks, MF2 validation and composition guards. Reviewed translations
  stay editable. Saving another edit opens and accepts a linked revision in one transaction;
  a failed save rolls back that new revision as well. Retries reuse the complete decision payload.
- Restore the normal **Pending / Decided** controls. Pending starts a linked round on the same
  row in the same open project, keeps the current translation, and updates project progress.
  Decided reviews the unchanged current translation without changing its text or status. Previous
  proposals and feedback remain in **Report**. A superseded finding is reviewed in its newer round.
  Reopening an unchanged source/current translation preserves its reported suggestion, evidence
  and automation origin. Changed source, context or translation still requires a fresh review.
- Keep **View report** as the only action in the report header; there is no separate note icon or
  popover. Optional explanations remain under **Report → Review feedback** and the ordinary
  **Accept** action saves them with the decision. Record the observed choice
  (`KEEP_CURRENT`, `ACCEPT`, or `EDIT_ACCEPT`); do not infer that either version is linguistically
  wrong merely from the choice. Exact reviewed-state receipts continue to prevent repeat reports.
  After completion, saved outcomes and notes remain in **Report**. Use the ordinary **Pending**
  control to reopen the latest round. Earlier decisions remain in history.
  The report header shows **Reviewed**, **Awaiting feedback**, or **Replaced** as appropriate and
  remains accessible in every state. Do not invent a confidence percentage.
- Keep optional original/suggestion assessments and explanation collapsed under
  **Report → Review feedback**. The inline feedback widget replaces the bottom **Comment on
  translation** and **Decision notes** fields for incident and known-AI reviews. Show it on a
  changed reported translation, including immediately after **Use suggestion**. Keep unsent
  older comments/notes accessible through a compact **Unsent notes** disclosure; fields already
  represented by the widget are not duplicated. Ordinary reviews without this feedback flow
  retain their standard fields. Saving retained notes uses the ordinary guarded save, including
  on completed editable reviews. Keyboard navigation skips hidden or disabled fields.
  A metadata-only save records `KEEP_CURRENT` and dismisses the report when the reviewed text is
  retained; it must not report a translation correction. Preserve the actual saved variant receipt
  when comment changes create a new immutable variant. An original marked bad cannot be accepted
  unchanged just by adding notes. An assessment of an earlier original does not carry onto a
  different translation when a completed review starts a new round.
  Resetting a translation edit preserves feedback; a subsequent feedback-only Reset clears it.
- **Report** retains the human decisions and agent responses. The ordinary **History**
  tab shows translation history. Keep the UI to the compact report strip and normal editing;
  clarification uses the existing chat. The backend follow-up APIs remain available, but there are
  no **Ask for another proposal** or **Defer** controls in this UI. Pending and editing keep the
  current project; the older separate-project REST endpoint remains compatible with existing callers.
- Chat follow-ups receive the recorded finding as context for Mojito's configured review model.
  Keep the conversation while changing review state or returning to a string in the same session.
  Retaining messages does not make old suggestions valid against changed source/current text.
  Reusing this chat does not send a durable response to the originating agent; structured review
  feedback remains the durable agent handoff. Keep that distinction explicit.

Backend outcomes include accept proposal, edit and accept, keep current, and defer. Accept and edit-and-accept immediately
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
  Review Project assignment/access checks. Human saves use the guarded decision flow with the
  exact proposal revision/version, review revision, and a stable retry ID. Same-row Pending uses
  `/reopen`; editing a completed review uses `/reopen-and-save` to create and save its successor
  atomically under the same access and current-state checks.
- Review Projects use the standard editor and review chat, with a persistent original/proposal
  report strip. **Use** stages either version and **Accept** saves the decision and optional note.
  The admin-only **Report** tab contains rationale, verifier notes, optional
  assessments, and feedback history. Non-admin project responses omit verifier notes,
  integrity diagnostics, and evidence; project feedback-history and evidence-artifact reads
  require admin access. The compact original/proposal/finding strip and guarded review decisions
  remain available to assigned reviewers. A current source, context comment, or translation
  change blocks acceptance of the old proposal. Structural integrity checks apply to every accepting
  reviewer, including administrators.
- Attach external evidence as `{label,url}` or same-run uploaded evidence as
  `{label,artifactSha256}` in the proposal's evidence array. Administrators can read those exact
  referenced artifacts through a project-authorized endpoint; they cannot browse other run
  artifacts through that endpoint.
  Raster images open inline; other content downloads with safe content-type handling.
- V111 adds the three review tables; V112 adds the historical incident/project links and incident
  filters. V115 adds queued routing and the batch lookup index; V116 adds automation source/type;
  V117 adds the exact human-reviewed state receipt; V118 adds resumable batch cursors and seek
  indexes; V119 adds active intake identities, submission aliases and explicit incident team scope.
  Existing Review Projects retain their workflows. There is no
  Codex transcript database, cross-machine agent scheduler, arbitration service, or voting UI.

## Validation and rollout

September 14 scalability validation: 121 focused backend tests pass without automatic retries,
including cross-run intake races, receipt team/type isolation, 10,002-row sweeps, overlapping
batch creation, the 25-project cap and automation continuation. The frontend suite passed 1,360
tests; the final empty-pass recovery fix passed all 19 create-page tests, TypeScript and production
build. Local MySQL 8.0.43 SQL checks used 1,001,000 synthetic incidents plus 1,000,000 proposals and
1,000,000 linked feedback rows. Forced covering seeks read 501 entries per branch (about 0.84 ms
median); a scoped 500-fingerprint receipt query took about 3.04 ms. These are warm SQL measurements,
not end-to-end API or production throughput. Representative concurrent load, production migration
cost and production query plans remain rollout checks.

The automated checks cover core state transitions, real database transactions and lease races,
idempotent ingestion/routing/decisions, exact feedback lineage, project deletion, authorization,
artifact access, stale edits, and frontend behavior. Local browser checks cover editor/chat acceptance,
feedback-only outcomes, stale rows, and narrow layouts. Deployment still requires the matching
backend, schema migrations, frontend, and MCP tools together.

September 14 batch/re-review validation: 116 focused backend tests pass with
`-Pno-local-config`, including real HSQL transactions, concurrent batch assignment, migration
defaults, queued intake, exact-state suppression, ordinary incident provenance, and assigned-reviewer
re-review. The final frontend suite passed 1,356 tests across 107 files, plus TypeScript, scoped lint,
and the production build. This includes the queue-first global scope and optional narrowing filters.

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

September 14 browser checks against the disposable MySQL8.0 database applied V115–V117,
created a three-finding global manual batch, ran a disabled incident automation twice (one project,
then zero), and opened an immutable linked re-review round. Creation preserved current variants,
text and status; original decisions survived re-review. A final create-page regression catches
accidental repository/feature requirements in global mode (17 focused UI tests pass).

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
