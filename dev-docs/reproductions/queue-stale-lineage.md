# Stale Pull-Run Lineage Reproduction

This is a **known-failing historical diagnostic**, not a passing regression or a
claim that pull-run lineage is fenced. The patch applies to queue commit
`6f5567426ee34a9207c8f15191f09e9f5b9e524f`, before tracked-work containment.
That revision is retained locally as
`ja/codex/queue-before-tracked-containment-20260909`; it is not promised to be in
the rewritten branch's published ancestry. The patch is archived evidence for
continued fencing work, not a fresh-clone CI command.
Use a separate disposable checkout of that revision; never apply it over ongoing
work or point the service tests at a non-disposable database.

```sh
git apply --unidiff-zero /absolute/path/to/queue-stale-lineage-6f5567426e.patch
mvn -pl webapp -am -Pno-local-config \
  -Dskip.npm=true -Dskip.installnodenpm=true \
  '-Dtest=AssetLocalizeAsyncJobLineageRetryIntegrationTest#expiredWorkerCannotReplaceWinningLineage' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dspring.datasource.url=jdbc:hsqldb:mem:queue_stale_lineage;hsqldb.tx=mvcc' test
```

The test pre-creates the pull-run and asset parents and uses the explicit `fr-FR`
tag. A private spy of the real lineage service blocks worker A after it selects an
old translation, but before replacing associations. Only a private TMService copy
uses that spy; cached Spring singletons are not rewired. The real queue runtime
claims A with a one-second lease and no renewal. Worker B reclaims after expiry,
selects the new translation, commits its lineage, persists `DONE`, publishes its
private output, and finishes the same PollableTask. Only then does the test release
A. Both executors are drained before releasing their dependencies.

Observed on 2026-09-09 UTC with real generation, business transactions, blob and
PollableTask services on HSQL, and the in-memory queue control:

- B's output contains the new translation and B's exact association is committed.
- Resumed A's queue completion is rejected and B's `DONE` payload/output survive.
- The final lineage assertion fails: the old variant replaces B's variant.
- Surefire's configured rerun reproduces the same failure with fresh fixture IDs.

Log: `/tmp/queue-stale-lineage-repro.log`. The first run expected variant 3 but
found variant 2; the rerun expected 6 but found 5. Both reached the final lineage
assertion, not a timeout, duplicate key, missing parent, or malformed tag failure.
This does not test database-backed reclaim or process crashes. Separate JDBC queue
contract tests cover lease transitions; they cannot certify these business effects.

Current containment excludes every non-null `pullRunName` from queue routing,
including empty and whitespace-only values. The normal suite verifies that policy
and rejection of rows from older producers. It does **not** invert this diagnostic's
assertion to treat stale lineage as acceptable. Re-enable tracked queue execution
only after implementing durable winner/scope authority and turning this failing
contract into a passing regression against that implementation.
