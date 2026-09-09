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
six-speed slider and an independent **Automatic review** toggle. Changing speed preserves whether
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
Omitted selectors use the saved preset. A request mixing `presetId` with legacy `profileId` or
`reasoningEffort` is rejected.
Explicit legacy requests keep their model/effort configuration path, while old queued jobs retain
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
Submission resolves the authenticated actor and selected settings, including the actual reasoning
effort, into the prepared request before queuing `AiReviewConfiguredChatJob`. That frozen input
survives the API-to-worker boundary; subsequent preference changes do not alter the job. Already
queued `AiReviewChatJob` inputs use their stored task owner and legacy configuration. The synchronous
`POST /api/ai/review` also uses the configured review path. Both job types restrict results to their
creator. Outputs use existing pollable-task blob retention (`MIN_1_DAY`); expiry requires a new
request. Stopping browser polling does not cancel queued or running provider work.

Submission returns a task ID without waiting for the model. Shared Quartz and pollable-task output
storage let submission and polling reach different API pods. The client retries transient polling
failures against the same task, bounds its wait to twenty minutes, and stops polling on navigation.
Page request guards keep late results from appearing on another text unit. Expected provider failures
are stored in the job result; generic Quartz completion does not imply a successful review. Inspect
submission, polling, and provider outcomes separately when assessing interactive reliability.

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
poll. Recovered job execution can create another row. Timing starts during execution, excluding
queue delay and browser polling. A completed provider call does not prove the browser received it.
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
