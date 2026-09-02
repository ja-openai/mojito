# Review Project deployment and client evidence — September 1, 2026

The historical read-only investigation found the prior editor, duplicate-save
and draft-ownership safeguards in both the sampled server's source revision and
a freshly fetched frontend bundle. That evidence did not identify the executable
JavaScript in an affected reviewer's earlier tab. Deployment-specific captures
remain local and are excluded from this public note.

## Evidence boundaries

| Evidence | What it establishes | What it cannot establish |
| --- | --- | --- |
| Source ancestry | A fix belongs to a particular source revision | That revision was built or deployed |
| Build configuration | Which source the build intended to use | The running server or served frontend bytes |
| Running server version | The server's embedded source revision | A browser's loaded frontend revision |
| Fresh entry document and downloaded bundle | The frontend artifact currently served, including inspected safeguards | What an earlier or long-lived tab loaded |
| Cache headers and application lifecycle code | Relevant refresh and caching behavior | That caching caused a particular failure |
| Decision/database records | Persisted row identities and outcomes | The originating suggestion, interaction or browser state |

No service worker or application release-check handler was found in the sampled
frontend. A long-lived tab could therefore retain earlier JavaScript; this is a
possible explanation requiring client evidence, not an incident attribution.
The available historical request evidence did not close that gap. An absence of
matching records outside a log's retention window cannot establish that the
requests did not occur.

## Evidence for a recurrence

Record a build-time frontend identifier from the executable bundle on decision
requests, together with the selected row and save outcome. Reading the current
server version when saving would incorrectly label an old tab as current.
A page-instance ID, monotonic decision sequence and suggestion-owner context can
connect adjacent saves without logging translation content. Client-supplied
diagnostics are not authorization or trusted proof of ownership.

After an authorized release, verify the running server and fresh served bundle
separately, and require existing translator tabs to reload. Preserve the
distinction between this release check and identifying a past interaction. See
[carryover findings](2026-09-01-review-project-carryover.md) and the
[latest local verification](2026-09-02-review-project-confidence-verification.md).

Original deployment captures are retained only in the ignored local
`target/review-project-investigation/deployment/` directory. The original note
is also preserved under
`target/review-project-confidence-20260902/checkpoint-prep/original-files/`.
Neither local artifact directory is part of the public change.
