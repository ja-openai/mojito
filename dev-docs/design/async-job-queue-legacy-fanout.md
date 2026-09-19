# Durable fanout for the existing parallel asset API

## Scope

The existing `POST /api/assets/{id}/localized/parallel` response remains a PollableTask.
When global queue, asset adapter, producer and fanout flags are enabled, untracked
requests register a durable parent before returning. The queue store must be JDBC.
Every non-null `pullRunName` stays on Quartz. Already queued Quartz parents always
stay on Quartz, including when flags change. This does not add the separately
proposed keyed v1 HTTP admission protocol or safe client retries.

## Durable boundaries

1. Validate all requested locales in their repository and freeze their order and
   resolved output tags. Reject duplicate/blank output tags and more than 1,000
   locales before registration. No catalog-wide locale scan occurs.
2. Write one immutable UUID manifest through the existing `POLLABLE_TASK` blob
   route; read back and verify SHA-256. The source is stored once, not in queue
   rows or per-locale SQL payloads. These receipts use permanent input retention;
   automatic cleanup is intentionally not introduced.
3. In one explicit READ_COMMITTED JPA transaction, create the parent task and
   `asset_localize_fanout` receipt with expected child count. Resolve an uncertain
   registration commit by its unique input UUID. If resolution is unavailable or
   absent, return HTTP 500 without a guessed task ID. Existing clients automatically
   retry 429/503 responses even for repeatable POSTs, so those statuses are unsafe
   here. An operator must reconcile an ambiguous legacy request before replay.
4. The bounded worker reconciler locks the receipt and atomically creates *all*
   child tasks, enlisted queue rows and ordinal mappings. It commits `ACCEPTED`
   with them. Commit uncertainty is resolved by rereading the same parent; retries
   never invent new child IDs. `ACCEPTING` is a transaction-local state that never
   commits. No blob I/O occurs under this database lock.
5. Reconstruct the parent output map from committed slots; publish it, then mark
   the parent task finished and receipt `FINISHED`. Lost publication or finish
   acknowledgements retry the same output and IDs. No Quartz failure wrapper owns
   these parents.
6. Continue reconciling `FINISHED` receipts. For mapped terminal queue rows with
   unfinished tasks, reuse the existing terminal-result repair service; never rerun
   generation. Mark `COMPLETED` only when all mapped queue rows and task projections
   are terminal. This includes delivered failures, which still fail validation.

V123 stores the parent receipt and `(parent_task_id, slot_ordinal)` mappings, with
unique child task and queue IDs. The due-work index is
`I__ASSET_FANOUT__DUE(state,next_attempt_at,parent_task_id)`. Each pass claims at most
10 parents; each has at most 1,000 children. Claim deadlines use database time and
existing queue timestamp handling. Registered tasks have null timeouts so the
legacy zombie cleaner cannot fail accepted obligations. Corrupt/missing manifests
fail closed, remain outstanding and require operator attention; retries do not
replace their input or silently fall back to a legacy blob.

The input reader stays available when queue flags are disabled. Every task input
read pays one SQL roundtrip containing two indexed identity probes before legacy
blob fallback. All three input read forms honor durable references. The matched
baseline must use this same source/image so this overhead is included.

## Role flags and rollback

- API: global/adapter/JDBC on, producer+fanout on for new durable requests;
  consumer false.
- Worker: global/adapter/JDBC on, consumer true (the ordinary default); producer
  and fanout may remain false. Reconciliation ignores those admission switches.
- Fence new API producer/fanout admission first. Keep compatible workers, global
  and adapter flags enabled while draining registered parents and children.
- Outstanding receipt states are `PENDING`, `ACCEPTED`, and `FINISHED`. A
  `FINISHED` parent alone is not a completed localization cohort. Require no
  outstanding receipts, no unexplained queue/task debt, complete returned outputs,
  and successful comparison before disabling consumers/global flags.
- Read parent/task/job/output identities through explicit mappings, never by
  scanning all queue JSON. A `COMPLETED` receipt does not erase child errors.
- New runtime readers must be on every serving API and consuming worker before
  fanout admission. Do not downgrade while these receipts remain outstanding.

## Verification and limits

The real JPA fixture covers all-or-none acceptance, failure after child two,
commit-then-throw and rollback-then-throw, concurrent resumes, output-write-then-
throw, uncertain finalization, registration receipts, corrupt input and recovery
of the exact retained output after a lost terminal callback. HSQL runs locally;
required hosted MySQL and PostgreSQL
lanes execute the same fixture with V123. Unit tests cover API flags/tracked
fallback, preflight rejection, durable/legacy input reads and reconciler wiring.

These checks do not establish staging performance, matched output correctness,
network partition recovery, full process-kill behavior or safe automatic retry of
an ambiguous HTTP request. The staging test must retain normal parallel/all-locale
CI, correlate actual mapped JDBC rows, compare matching disabled controls, and
roll back on functional, performance or shared-health regression.
