# AI Observability

## Scope

This note documents the current AI Translate / AI Review metrics contract and the PromQL shapes
used for dashboard panels.

## AI Translate

Use `AiTranslateService_requestDuration_seconds_*` for provider-call latency and
`AiTranslateService_localeDuration_seconds_*` for whole-locale runtime. `localeDuration` includes
search/import work; `requestDuration` is the closer proxy for model latency.

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

Use `AiReviewChatWS_requestDuration_seconds_*` for interactive review chat latency and
`AiReviewService_requestDuration_seconds_*` for async/legacy review request latency. Both expose a
`result` tag with `completed`, `timeout`, `provider_failed`, or `failed`.

Interactive review p95 latency by locale:

```promql
histogram_quantile(
  0.95,
  sum by (le, locale) (
    rate(AiReviewChatWS_requestDuration_seconds_bucket[$__rate_interval])
  )
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

Async review p95 latency by locale:

```promql
histogram_quantile(
  0.95,
  sum by (le, locale) (
    rate(AiReviewService_requestDuration_seconds_bucket{mode="noBatch"}[$__rate_interval])
  )
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
