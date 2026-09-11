# AI Observability

## Scope

This note documents the current AI Translate / AI Review metrics contract and the PromQL shapes
used for dashboard panels.

## AI Translate

Human review quality evidence is available in the AI translation prompt settings **Learning** tab.
It joins imported attempt variants to Review Project `reviewed_variant_id` / accepted `variant_id`
and groups future requests by prompt fingerprint, model, reasoning effort, verbosity, and locale.
See `dev-docs/design/029-ai-translation-feedback-evaluation.md` for metric interpretation and the
prompt-tuning loop. This is a quality feedback view; request and locale timers below remain the
operational latency/error contract.

Use `AiTranslateService_requestDuration_seconds_*` for provider-call latency and
`AiTranslateService_localeDuration_seconds_*` for whole-locale runtime. `localeDuration` includes
search/import work; `requestDuration` is the closer proxy for model latency.

No-batch lineage is stored separately from translation comments. `ai_translate_text_unit_attempt`
keeps the per-string audit row, anchored to the pollable task and linked to `ai_translate_run` when
a run row exists. Attempt status is stored as an open `varchar` and handled in code with string
constants, not a database or JPA enum, so adding an attempt status does not require a schema change.
Heavy provider payloads live in `AI_TRANSLATE_LINEAGE` blobs: one request JSON blob per grouped
Responses request and one response JSON blob when the provider response is received. Attempt rows
share those blob names through `request_group_id`, so the payload is not copied once per text unit.
Inline image data URLs are redacted before lineage payloads are written. Legacy batch mode continues
to rely on its existing batch import blobs and does not write normalized lineage rows. The no-batch
downloadable report keeps the lineage group id instead of embedding the raw provider request.
The frontend text-unit history timeline fetches these attempt rows separately and links to the
scoped attempt summary plus redacted request/response payloads when a payload exists, including
from review-project detail. The system AI Translate settings page also exposes lineage as a
drill-down on recent runs so operators can check whether normalized rows were written even when a
text-unit history row only has the legacy translation comment.
For readability, the shared JSON viewer expands object or array JSON encoded in OpenAI
`input_text.text` and `output_text.text` fields. Other JSON-looking strings remain strings, and the
raw-payload link always serves the original stored content. Request payloads with a top-level
`instructions` string also offer a safe Markdown-rendered Instructions view while keeping JSON as
the default view.

P95 request latency by locale:

```promql
histogram_quantile(
  0.95,
  sum by (le, locale) (
    rate(AiTranslateService_requestDuration_seconds_bucket{mode="no_batch"}[$__rate_interval])
  )
)
```

Average request latency by locale:

```promql
sum by (locale) (
  rate(AiTranslateService_requestDuration_seconds_sum{mode="no_batch"}[$__rate_interval])
)
/
sum by (locale) (
  rate(AiTranslateService_requestDuration_seconds_count{mode="no_batch"}[$__rate_interval])
)
```

Timeouts and provider failures by locale:

```promql
sum by (locale) (
  increase(AiTranslateService_timeouts_total{mode="no_batch"}[$__range])
)
```

```promql
sum by (statusCode, locale) (
  increase(AiTranslateService_providerFailures_total{mode="no_batch"}[$__range])
)
```

Current no-batch queue depth:

```promql
AiTranslateService_requestsInFlight{mode="no_batch"}
```

## AI Review

Review Project and text-unit details expose one popup beside **AI Chat Review** containing a
speed slider and an independent **Automatic review** toggle. Fastest, Fast, and Balanced are
available to all reviewers; Thorough, Deep, and Ultra are admin-only to limit queue contention. Changing speed preserves whether
automatic review is enabled; toggling automatic review preserves the selected preset. The speed
button remains available when the section is collapsed and shows **Auto off** when paused.
Provider model names stay in backend configuration. The six provider presets are:

| Preset | Model | Reasoning effort | Service tier |
| --- | --- | --- | --- |
| `fastest` | `gpt-5.6-luna` | `none` | `priority` |
| `fast` | `gpt-5.6-sol` | `none` | `priority` |
| `balanced` (account default) | `gpt-6-astra` | `low` | `priority` |
| `thorough` | `gpt-6-astra` | `medium` | `priority` |
| `deep` | `gpt-6-astra` | `high` | `priority` |
| `ultra` | `gpt-6-astra` | `max` | `priority` |

Override a preset with `l10n.ai-review.interactive.presets.<id>.model-name`,
`reasoning-effort`, and `service-tier`. For example:

```properties
l10n.ai-review.interactive.presets.fastest.model-name=gpt-5.6-luna
l10n.ai-review.interactive.presets.fastest.reasoning-effort=none
l10n.ai-review.interactive.presets.fastest.service-tier=priority
l10n.ai-review.interactive.presets.ultra.model-name=gpt-6-astra
l10n.ai-review.interactive.presets.ultra.reasoning-effort=max
l10n.ai-review.interactive.presets.ultra.service-tier=priority
```

`priority` requests API Fast mode independently of reasoning effort. All six default models support
Fast mode; Astra with EU data residency requires a Standard (`default`) tier override. The response's
actual model and service tier are recorded separately because the provider can downgrade a request.
See [Fast mode](https://developers.openai.com/api/docs/guides/fast-mode) and
[model/tier availability](https://developers.openai.com/api/docs/pricing?latest-pricing=fast).
Ultra is the preset's display name; its API effort is `max`, not `ultra`.
These labels describe intended speed/effort choices, not a measured latency or quality ranking.

The account saves `aiReviewPreset` and `aiReviewAutomaticDisabled` independently. The speed slider
PATCHes only the preset; the automatic-review toggle PATCHes only the disabled flag.
New clients send only a `presetId`; the server resolves the whole model/effort/tier combination.
Omitted selectors use the saved preset. For non-admins, a saved Thorough, Deep, or Ultra selection
runs as Balanced without rewriting the stored preference. Explicit requests for those presets are
rejected before queuing; legacy medium/high requests also require an admin, and a saved legacy
medium/high effort falls back to low for non-admins. A request mixing `presetId` with legacy
`profileId` or `reasoningEffort` is rejected.
Permitted explicit legacy requests keep their model/effort configuration path, while old queued jobs retain
legacy behavior. Background/legacy review and glossary AI continue using their existing configuration;
provider Batch omits the online processing tier. New presets share the configured
`l10n.ai-review.responses.text-verbosity`.

Automatic requests wait for settings to load and stay paused while automatic review is disabled.
For an empty conversation with automatic review off, the chat row shows **Review** when the input
is empty or whitespace. It runs one review with the selected preset without enabling automatic
review. Typing changes the same button to **Ask**; after a conversation starts, it remains **Ask**.
Changing the preset clears the conversation and ignores late results from the previous selection.
These choices do not change the shared prompt or constitute a quality comparison.

Interactive clients submit `POST /api/ai/review/jobs` and poll `GET /api/ai/review/jobs/{taskId}`.
Submission freezes the authenticated actor and selected settings in a prepared request, creates an
existing pollable task, and starts the asynchronous provider call directly on the API instance.
New interactive requests do not create Quartz jobs. The legacy `POST /api/ai/review` response contract
uses the same bounded task lifecycle through asynchronous servlet completion. Reads and cancellation
remain restricted to the task creator. Outputs retain existing blob retention (`MIN_1_DAY`).

The task stays pending until its result is persisted. A completion callback stages a canonical
result in the task's existing JSON message, writes the output blob, and then marks the task finished.
If output storage fails, a bounded cleanup pass retries the immutable staged result. Polling may
reach any API pod. The client polls the same task and cancels it with
`DELETE /api/ai/review/jobs/{taskId}` on navigation, including navigation during submission.
Cancellation is durable; local calls are cancelled immediately, and other instances detect it
through periodic checks. Cancellation propagates to the underlying HTTP request.

### Interactive provider capacity and deadlines

`l10n.ai-review.execution.max-in-flight` defaults to **400 across all instances**, independent of
Quartz threads or replica count. `max-in-flight-per-user` defaults to **3 per authenticated user**,
including admins, shared across instances. These are approximate concurrent-request limits, not
requests-per-minute limits or a separate HTTP connection pool. New interactive reviews do not wait
in a Quartz backlog; HTTP server threads, database connections, and provider execution still have
their own capacity and waiting behavior.

Capacity reservations retain the existing JSON format in the uniquely named, non-expiring `MBlob`
row `ai_review_execution/v1/capacity`. Admission reads its current scalar content directly from the
database and conditionally updates it only if the content still matches that snapshot. It makes at
most three attempts in separate transactions bounded to two seconds, with a one-second timeout on
each write statement.
Capacity transactions never run inside the per-task transaction. They do not use `SELECT FOR UPDATE`;
the conditional MySQL update still takes a brief row lock and is bounded by its statement timeout.

A valid snapshot at either limit returns a retryable **429 busy** result. If a valid snapshot is below
both limits but concurrent updates defeat all reservation attempts, admission proceeds as
`best_effort` without adding a reservation. Failed reads or invalid stored state remain errors;
they do not justify this fallback. Counts can therefore understate active work, and either limit
can temporarily be exceeded. A failed release can instead overstate active work until a later
release succeeds or the reservation reaches its original deadline, normally 180 seconds after task
creation. The limits remain at 3 and 400 while admission outcomes are measured.

This approximation is intentional. The previous global `NOWAIT` row lock could turn overlapping
requests from different users into busy responses even when both limits had room. An optional
interactive translation assistant benefits more from availability during brief contention than
from a strict cluster semaphore. The shared counts still discourage a user from filling provider
capacity, but they are not an exact quota or a guarantee against every concurrent request burst.
The implementation separates task state in
[`AiReviewExecutionStore`](../../webapp/src/main/java/com/box/l10n/mojito/service/oaireview/AiReviewExecutionStore.java)
from accounting in
[`AiReviewCapacityStore`](../../webapp/src/main/java/com/box/l10n/mojito/service/oaireview/AiReviewCapacityStore.java).

The per-task lifecycle remains exact: claim, cancellation, and completion keep their row lock and
explicit refresh to avoid stale request-scoped JPA state. No provider work starts before the durable
task claim commits. Attempt tokens fence task result writes and reservation release. Completed output
is materialized before its capacity release attempt, and failed releases retry independently of
result persistence. These bounded accounting calls still share the completion executor, so sustained
database contention can delay later completions.

`AiReviewExecution.admission` records `reason={reserved|user_limit|global_limit|best_effort}` for
admission decisions and `reason={optimistic_conflict|bounded_write_contention}` for conflicting
attempts. A recognized write timeout records both conflict reasons, and a request can make multiple
attempts, so summing every reason is not a request count. `AiReviewExecution.capacityRelease` records
`reason={optimistic_conflict|bounded_write_contention|deferred}` for release contention and exhausted
release attempts. These metrics distinguish actual observed limits from accounting contention
before changing the configured limits.

`l10n.ai-review.execution.timeout-seconds` defaults to **180 seconds overall**, measured from task
creation. Each provider attempt uses the smaller of its adaptive timeout and the remaining overall
budget, with at most three attempts. Expired, cancelled, or finished tasks cannot initiate a call
or retry. Cancellation attempts to release its reservation after the underlying HTTP future
terminates or acknowledges cancellation. After process loss, the reservation expires at the
original deadline. Client HTTP cancellation cannot establish whether the remote provider has
stopped computing.

A process restart may fail an in-flight review. A five-second cleanup pass scans unfinished review
tasks with bounded keyset pages, materializes staged results, and fails expired or abandoned calls.
It never resubmits an uncertain request. Generic pollable-task zombie cleanup excludes these tasks
to preserve the guarded lifecycle. Old Quartz chat jobs remain compatible for draining and skip
provider execution once terminal or expired. AI Translate and background review retain their
existing scheduling and completion behavior. No schema migration is required.

Automatic Ultra requests fall back to Balanced, including frozen inputs from older deployments.
The saved Ultra preference remains available for permitted manual requests. The temporary policy is
controlled by `l10n.ai-review.interactive.ultra-automatic-enabled=false`.

Direct requests use configurable adaptive timeout multipliers (`medium=4`, `high=6`, `xhigh=8`,
`max=12`), capped at 300 seconds by default. These budgets are not measured latency or quality gains.
See `032-ai-translation-quality.md` for the translation/review contract and evaluation plan.

Use `AiReviewChatWS_requestDuration_seconds_*` for interactive review chat latency and
`AiReviewService_requestDuration_seconds_*` for async/legacy review request latency. Both expose a
`result` tag with `completed`, `timeout`, `provider_failed`, or `failed`.

### Usage metadata and inspection snapshots

Migration `V109__AI_Review_Request_Usage.sql` adds `ai_review_request_usage`. Each logical review
execution records the requester, optional pollable-task/text-unit IDs, locale, surface,
`request_type` (`automatic`, `manual`, `follow_up`, `retry`, or `legacy`), selected preset or legacy profile, resolved
model/reasoning, requested/returned tier and model, outcome, timestamps, and duration. The existing
`reasoning_effort` column records the actual resolved effort frozen into the request; historical
rows retain their original settings when preset mappings change.

Migration `V110__AI_Review_Request_Transcripts.sql` adds nullable `request_json` and `response_json`
columns to that row. At execution start, `request_json` captures the submitted request after locale
normalization: source, target, source description, text-unit ID, selectors, request metadata, and
all submitted message roles/content, including page context and earlier assistant text. On success,
`response_json` captures the exact response returned by the review endpoint: assistant message,
suggestions, and review assessment. Recorded failures retain the request and outcome with no
assistant response. Historical rows remain null; there is no transcript backfill.

These snapshots support internal inspection through the private usage repository/database; no
history endpoint or conversation restoration is added. Earlier suggestion cards are available in
their own request's response snapshot, since follow-ups submit only prior message text. The snapshots
are independent of temporary Quartz inputs and pollable-task output retention, and have no automatic
expiry. Provider message filtering and browser conversation behavior remain unchanged.

Recording is best effort: storage failures do not fail a review and can leave missing starts or
outcomes. A row spans the provider retry loop; it does not count each provider attempt or browser
poll. Timing starts during execution, excluding submission/admission time and browser polling.
Compare task creation with usage start for time spent after task creation and before provider work,
and usage duration for provider work. The first interval excludes any incoming HTTP queue wait,
authentication, and preference preparation before the task exists; it cannot establish that the
server is unsaturated. A completed provider call does not prove the browser received it. Durable
task claims and the capacity admission decision remain mandatory even when this optional inspection
recording fails; a `best_effort` admission need not have a capacity reservation.
Use this data to measure adoption, follow-up use, failures, and latency by selected model; it does
not measure linguistic quality or establish that one model is better.

### Legacy precompute

Review Project and text-unit details bypass legacy precomputed reviews for every request, including
targets without extra page context. Existing cache rows lack model/settings provenance, so they
cannot be attributed to the selected model. The proto API and stored `for-frontend-v2` runs remain
available to existing callers; their lookup counters do not measure cache use by these pages.
The reserved `for-frontend` name still maps to `for-frontend-v2` for new precompute work, while old
in-flight batches and custom run names retain their namespaces. Reuse requires model/settings and
prompt/glossary/context freshness checks plus a safe scoped PM/admin/scheduled precompute trigger;
translator page loads must not warm this cache.

Precomputed lookup outcomes are counted by `AiReviewWS.precomputedReviewLookup` with bounded tags:
`requestMode={cache_only|live_or_compute}` and `result={hit|miss|unreadable|stale|empty}`.

Prometheus currently exposes the AI Review timers as `*_sum`, `*_count`, and `*_max`, not as
histogram buckets. Use average/max panels instead of `histogram_quantile(...)` unless buckets are
enabled later.

Interactive review average latency by locale:

```promql
sum by (locale) (
  rate(AiReviewChatWS_requestDuration_seconds_sum[$__rate_interval])
)
/
sum by (locale) (
  rate(AiReviewChatWS_requestDuration_seconds_count[$__rate_interval])
)
```

Interactive review max observed latency by locale:

```promql
max by (locale) (
  AiReviewChatWS_requestDuration_seconds_max
)
```

Interactive review request count by locale:

```promql
sum by (locale) (
  increase(AiReviewChatWS_requestDuration_seconds_count[$__range])
)
```

Interactive review timeout/provider failure counts:

```promql
sum by (locale) (
  increase(AiReviewChatWS_timeouts_total[$__range])
)
```

```promql
sum by (statusCode, locale) (
  increase(AiReviewChatWS_providerFailures_total[$__range])
)
```

Async review average latency by locale:

```promql
sum by (locale) (
  rate(AiReviewService_requestDuration_seconds_sum{mode="noBatch"}[$__rate_interval])
)
/
sum by (locale) (
  rate(AiReviewService_requestDuration_seconds_count{mode="noBatch"}[$__rate_interval])
)
```

Async review max observed latency by locale:

```promql
max by (locale) (
  AiReviewService_requestDuration_seconds_max{mode="noBatch"}
)
```

Async review request count by locale:

```promql
sum by (locale) (
  increase(AiReviewService_requestDuration_seconds_count{mode="noBatch"}[$__range])
)
```

Async review timeout/provider failure counts:

```promql
sum by (locale) (
  increase(AiReviewService_timeouts_total{mode="noBatch"}[$__range])
)
```

```promql
sum by (statusCode, locale) (
  increase(AiReviewService_providerFailures_total{mode="noBatch"}[$__range])
)
```

Review-project precompute cache hit rate:

```promql
sum(
  increase(AiReviewWS_precomputedReviewLookup_total{requestMode="cache_only", result="hit"}[$__range])
)
/
sum(
  increase(AiReviewWS_precomputedReviewLookup_total{requestMode="cache_only"}[$__range])
)
```

Unreadable precompute cache rows:

```promql
sum(
  increase(AiReviewWS_precomputedReviewLookup_total{requestMode="cache_only", result="unreadable"}[$__range])
)
```

Stale precompute cache rows:

```promql
sum(
  increase(AiReviewWS_precomputedReviewLookup_total{requestMode="cache_only", result="stale"}[$__range])
)
```

## Client pool

`OpenAIClientPool` metrics are emitted per `pool` tag (`ai-translate`, `ai-review`).

Blocked submissions:

```promql
sum by (pool) (
  increase(OpenAIClientPool_blockingSubmissions_total[$__range])
)
```

Wait time for a semaphore permit:

```promql
histogram_quantile(
  0.95,
  sum by (le, pool) (
    rate(OpenAIClientPool_acquireWaitDuration_seconds_bucket[$__rate_interval])
  )
)
```

Available permits and currently blocked callers:

```promql
OpenAIClientPool_availablePermits
```

```promql
OpenAIClientPool_waitingSubmissions
```

## Pollable jobs

Quartz timers remain useful for scheduler/job latency, but they are not a replacement for request
timers around the OpenAI calls themselves.

```promql
histogram_quantile(
  0.95,
  sum by (le, name) (
    rate(QuartzPollableJob_timeFromExecutionToFinish_seconds_bucket[$__rate_interval])
  )
)
```

```promql
count by (name) (
  QuartzPollableJob_timeFromExecutionToFinish_seconds_count
)
```
