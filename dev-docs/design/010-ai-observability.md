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

AI Review defaults to `max` reasoning, `low` text verbosity, and `default` online processing. Override
these with `l10n.ai-review.responses.reasoning-effort`, `text-verbosity`, and `service-tier`.
Interactive review and glossary AI share these settings; provider Batch uses the reasoning and
verbosity settings but omits the online processing tier. The provider response retains the actual
`service_tier`, which can differ from the requested tier. It is available in captured response
payloads; existing model/locale metric tags alone do not prove the requested processing tier was used.
Direct requests use configurable adaptive timeout multipliers (`medium=4`, `high=6`, `xhigh=8`,
`max=12`), capped at 300 seconds by default. These budgets are not measured latency or quality gains.
See `032-ai-translation-quality.md` for the translation/review contract and evaluation plan.

Use `AiReviewChatWS_requestDuration_seconds_*` for interactive review chat latency and
`AiReviewService_requestDuration_seconds_*` for async/legacy review request latency. Both expose a
`result` tag with `completed`, `timeout`, `provider_failed`, or `failed`.

Interactive clients submit the full chat request to `POST /api/ai/review/jobs` and poll
`GET /api/ai/review/jobs/{taskId}`. Submission returns a task ID without waiting for the model;
each poll returns pending, the completed review, or a terminal error. This lets configured reasoning
finish without holding a browser request open beyond an ingress deadline. The model, reasoning,
prompt, glossary and integrity context, provider retries, and response validation are unchanged.
The legacy synchronous `POST /api/ai/review` remains available for existing clients.

Jobs use the existing shared Quartz scheduler and pollable-task output storage, so submission and
polling can reach different API pods. Task data is restricted to its creator. Outputs use the existing
minimum one-day retention; an expired result requires a new review. The client retries transient
polling failures against the same task, and stops polling on navigation. Stopping polling does not
cancel already queued or running provider work. Existing page request guards prevent a late result
from appearing on another text unit. The client wait is bounded to twenty minutes.

The chat duration metric measures job execution, excluding queue delay and browser polling. A
completed model call alone does not prove that a client received the result; inspect submission,
polling, and provider errors separately when assessing interactive reliability.
Expected provider failures are stored in the job's error result, so generic Quartz completion counts
describe task execution rather than successful reviews. Use the dedicated review status and metrics
for provider outcomes.

Review-project pages first check `/api/proto-ai-review-single-text-unit` with
`onlyPrecomputed=true` when the automatic review has no page-only context messages. The backend reads
the `for-frontend-v2` cached run. The public `for-frontend` run name maps to this version for new
precompute work, so old context-free reviews are not reused as current-policy reviews. Already
scheduled old batches retain their stored run names on import. Custom run names are unchanged;
future glossary/model/configuration edits do not automatically invalidate cached rows.
Cache hits are rendered without calling the live interactive review
endpoint, after the cached variant is revalidated through the existing text-unit lookup. If the page
has deterministic warning context or matched glossary context, the review page skips the precomputed
cache and calls live review so speed does not weaken review quality. Cache misses, unreadable cache
rows, stale cached rows, and cached rows that contain no useful review content fall through to the
live review path so translators do not see an empty AI panel. Cached output is considered useful when
it contains a target suggestion, alternate suggestion, a complete existing-target rating with a
`0..2` score and explanation, a review-required reason, or a `reviewRequired=true` flag. The
translator page should not start provider work just to warm this cache; precompute should come from
an explicit PM/admin/scheduled path that calls the async proto review job ahead of translator review.

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
