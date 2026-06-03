# Asynchronous Queue Handlers

Status: proposed follow-up from the 2026-09-11 discussion, not implemented or
enabled. This extension is separate from landing the existing default-off queue.
It does not authorize a merge, new migration, provider call, or workload cutover.

## Current Behavior And Direction

At queue revision `ac324fd649`, `AsyncJobHandler.process` returns an
`AsyncJobHandlerResult` synchronously. The runtime immediately applies DONE or
REQUEUE, then cancels renewal and releases local capacity. Starting a provider
future and returning DONE would acknowledge dispatch, not provider completion.
`onJobDone` is a best-effort notification after the terminal transition, not a
deferred completion API.

At local master `b64352ba75`, interactive AI Review already uses the dedicated
`openAIClientReview` client, provider futures, durable task cancellation and
deadline checks. Its dispatcher has no waiting queue. The frontend sends DELETE
on cancellation; local futures also observe cancellation handled by another pod.
The global occupancy setting is warning-only, not a cluster-wide execution cap.
These are source observations, not fresh test or deployment evidence.

Recommended direction: extend the generic engine with optional asynchronous
completion, retaining existing synchronous handlers. Use a separate named
`ai-review` queue and explicit policy, not a second persistence/lease engine.
Keep authenticated task access, conversation sequencing, model policy, per-user
fairness and provider-specific cancellation outside the generic store.

## Execution Contract

The proposed asynchronous handler returns a completion stage without blocking a
worker on `get` or `join`. Existing synchronous handlers must remain synchronous
on their bounded executor; wrapping their result must not run expensive work on
the polling or heartbeat scheduler. The public API shape remains to be reviewed.

Three lifetimes must remain distinct:

| Resource | Ownership ends when |
| --- | --- |
| Dispatch thread | Handler setup has returned its stage. |
| Queue claim | A fenced terminal/requeue transition succeeds, or ownership is lost. |
| Provider occupancy permit | The local transport is confirmed settled, or an explicit bounded abandonment policy applies. |

A pending stage still consumes an in-flight operation slot, even when no worker
thread is occupied. Renew its valid lease until transition, ownership loss, or
the operation's absolute deadline. Normal completion must use a bounded,
queue-owned completion executor; do not run JDBC, application callbacks or
expensive parsing on the HTTP client's event loop. Pass immutable values, not a
thread-bound EntityManager, request security context or open business transaction.

Lease renewal and request deadlines are independent. The immutable request
deadline starts at admission and includes waiting time. Neither heartbeat,
dispatch, retry nor restart may reset it. Expired or cancelled pending work must
not start a provider call. Background-job retry policy remains unchanged.

Cancellation is a state transition plus cooperative transport cancellation, not
merely `CompletableFuture.cancel(true)`. Completion, cancellation and timeout must
arbitrate through persisted task/attempt ownership; a losing late result cannot
overwrite the winner. No promise is made that remote computation or billing has
stopped. A completed/cancelled public future does not by itself prove physical
transport settlement, so capacity must not be released and reused blindly.
An active-state check cannot atomically fence an external HTTP side effect. If
cancellation or lease loss races transport creation, cancel its handle once
published and reject its late result; do not claim the remote call never started.

The completion stage's registration can race immediate completion, handler setup
failure, shutdown and timeout. All paths must retire local bookkeeping once,
retain the existing queue-row fencing/unknown-commit semantics, and avoid making
missed terminal callbacks a new durable publication mechanism. Executor rejection
must have an explicit bounded failure/recovery path, not leave renewal running
forever or report a success whose transition was never attempted.

Shutdown must stop claims and drain pending operations, not just wait for an empty
worker executor. Keep renewal and completion infrastructure alive through the
bounded drain. After abandonment, late callbacks must not mutate a replacement
attempt or access a restarted runtime's resources. Preserve visibility of fatal
errors; stage callbacks must not silently swallow them into an ignored future.

## Interactive Policy

For the proposed first AI Review adapter:

- Bound both pending work and active provider calls. Define the scope of each
  limit explicitly: a per-pod limit is not a cluster-wide cap. Existing occupancy
  warnings cannot serve as a hard dispatch semaphore.
- Expire old requests and give waiting users a fair opportunity to run; do not
  build an unbounded backlog and rely on frontend retries to drain it.
- Keep frontend cancellation and the original end-to-end deadline. Offer manual
  retry. The initial adapter must preserve the existing bounded in-attempt
  provider retry policy; changing its status classification or adding jittered
  backoff is a separate behavior change, not implicit in queue enrollment.
- Do not automatically replay an execution after process death, lease loss or
  an ambiguous outcome. Restart failure is acceptable here.
  A one-claim attempt budget is a candidate building block, not proof of complete
  admission, cancellation or transport behavior. Failure before the first actual
  provider call is possible with this policy and must be reported honestly.
- Keep chat turns sequenced within a conversation. FIFO claims across multiple
  workers do not guarantee completion order; independent reviews can overlap.

At master `b64352ba75`, the AI Review wrapper permits up to three
`getResponsesCall` invocations within one execution after HTTP 408, 429 or a
status of 500 or higher, subject to the original deadline and active-state checks.
A one-claim budget does not disable those retries. They do not prove that an
earlier provider attempt performed no work; this plan promises neither exactly
once nor one HTTP submission. A strict single-submission policy would require
separate review of the wrapper and underlying client retry behavior.

Durable task state does not make an in-memory future recoverable. Provider-side
idempotency or persisted operation identity/reconciliation would be required to
change the no-automatic-queue-replay policy. This proposal does not infer such
support. Manual retries are new user requests and can also duplicate remote work.

## Finite Delivery And Acceptance

1. Prepare the existing branch for a disabled landing independently: refresh its
   base, resolve migration history/collisions, and verify legacy shared paths.
   Flyway and shared task/blob/generation changes are not gated by routing flags.
   Neither this extension nor full Quartz replacement is a prerequisite for a
   separately reviewed, inactive foundation.
2. Add asynchronous completion as one engine change with no AI Review enrollment.
   Prove an incomplete stage releases its worker while retaining capacity and
   renewing its lease; immediate and delayed success/failure must match the
   synchronous contract. Cover null/throwing setup, fatal failures, cancellation,
   expiry, late completion after reclaim, unknown transition commits, completion
   executor rejection, and shutdown/restart with outstanding stages. Use gated
   tests with real executors and both JDBC dialects for ownership races.
3. Add the AI Review adapter only with a separately reviewed admission and
   cancellation contract. Test cancellation before dispatch and on another pod,
   cancel-versus-response races, elapsed queue deadlines, transport settlement,
   bounded/fair dispatch, conversation ordering and process death after provider
   submission. Count handler executions and provider invocations separately:
   permitted in-attempt response-status retries retain their existing bound;
   the crash/lease-loss/ambiguous-outcome lane must show recovery does not submit
   a replacement execution, not merely one queue-row completion. After observed
   lease loss, cancellation or deadline expiry, no new retry may be scheduled.
   Exercise the check-to-send race separately: a handle published after local
   cancellation must be cancelled and a late response cannot publish success.
   Keep existing routing off until the integrated behavior and rollback/drain
   path pass.

No SQL change is selected here. Persisted deadline/cancellation representation
and any upgrade depend on the existing applied-migration-history decision. Core
lifecycle work can proceed independently, but a return-type change alone does
not close interactive readiness.

## Review And Verification

This documentation-only plan was cross-checked against the revisions above and
independently reviewed. Review corrected a conflation of queue replay with the
existing provider retry loop; the policy and acceptance counters now distinguish
them. A fresh synchronous lifecycle baseline passed 87 tests with no failures,
errors, skips or reruns: `AsyncJobQueueRuntimeHandlerAdmissionTest`,
`AsyncJobQueueRuntimeHeartbeatTransitionTest` and `AsyncJobQueueCoordinatorQuiesceTest`
using `-Pno-local-config` (`/tmp/queue-async-handler-plan-baseline.log`). Existing
compiler deprecation and AspectJ weaving warnings remain. This run verifies the
current contract only, not the proposed API, AI Review behavior or real-database
ownership races. No runtime, schema, feature flag or deployment changed.

## Established References

- [Spring AMQP async return types](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-returns.html)
  acknowledge on asynchronous completion rather than method return.
- [Temporal async activity completion](https://docs.temporal.io/develop/java/activities/asynchronous-activity)
  separates activity-function return from execution completion using task identity.
- [gRPC cancellation](https://grpc.io/docs/guides/cancellation/)
  requires cooperative application/downstream cancellation rather than assuming
  that a client cancellation interrupts all remote work.

These are design precedents, not dependencies or evidence that our extension
already exists. See the [readiness ledger](async-job-queue-review.md) for current
verification, and the [library boundary](async-job-queue-library.md) for packaging.
