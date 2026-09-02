# Review Project editing state

## Purpose and scope

The September 1 investigation reproduced independent failures in row ownership,
background refresh, failed save-and-advance, and input composition. The September
2 frontend audit additionally reproduced lost edits during conflict recovery,
drafts following a changed source, transport retries after a session ended, and
acknowledgements replacing newer observed state. The page must retain a reviewer's
work and show a truthful save outcome under these conditions. The implementation
uses explicit editing sessions and save operations. The existing page layout and
API endpoints remain.

This is a local implementation and verification record. It does not establish
which client path caused an earlier reported carryover incident, and it is not
a deployment record. See the [investigation](../investigations/2026-09-01-review-project-carryover.md)
and [additional failure evidence](../investigations/2026-09-01-review-project-follow-up.md).

## State ownership

| State | Owner | Rule |
| --- | --- | --- |
| Latest server row | Project query cache | Receiving it does not implicitly replace a dirty draft. A refresh failure keeps the mounted editor. A delayed acknowledgement cannot replace an already observed different source or third revision. |
| Translation, status, comment, notes and reviewed source/revision | Row editing session | The draft and its original source identity and base are kept together. Clean sessions may adopt a refresh; dirty sessions retain their base until explicit reset or a matched successful save. |
| Input composition and editor callbacks | Row detail session | Incomplete composition cannot submit or navigate. Callbacks from a previous row cannot edit the active session. |
| AI conversation and suggestions | Row, locale and reviewed context | Old content cannot be paired with the new row's target-change handler. |
| Save/check/conflict/failure/success | One mutation operation | Each operation freezes its request before yielding and carries a stable operation ID through explicit recovery. Each attempt has its own ID. |
| Use external discard choice | Accepted recovery request | Capture the field values when that request starts. Its acknowledgement replaces only fields unchanged since that choice; newer edits remain unsaved. |
| HTTP attempts | Mounted user/project session | Recheck ownership immediately before each transport invocation, including automatic retries and requests resumed after reconnect. |
| Advance intent | Origin row and operation ID | Only that operation's success can advance. The intended following rows are captured before filters remove a newly decided row. |
| Terminology feedback, resolution and metadata forms | User, project, row and form | Dirty fields survive refresh and same-app navigation; acknowledgement clears only the submitted values, preserving newer edits. Metadata Cancel explicitly discards. |
| Find/Replace batch | User and project session | Each successful row is acknowledged immediately; retries retain unfinished work and its original revisions. Editing is disabled while applying. Obsolete completions cannot navigate or replace a new session. |

`useReviewProjectDraft` owns editable values, base and latest snapshots, and the
submitted values for an outstanding operation. New edits after submission remain
unsaved even when the earlier request succeeds. Reset is an explicit adoption of
the latest server snapshot and invalidates the old recovery action. Acknowledged
snapshots cannot be rolled back by older unchanged props or an outstanding GET.
Project-metadata responses preserve the row cache and request fresh detail.

The retained base includes the underlying TM text unit ID, source text and
message format. A review row ID alone is insufficient: a glossary definition edit
can remap that row to another source. A dirty draft remains paired with its
original source and editor format, shows the updated source separately, and
blocks saving, decision-state changes and conflict recovery until explicit Reset.
Reset adopts the updated source and server values; a clean draft can adopt them
directly. Save requests take their source identity from the retained base.

An absent `messageFormat` remains `undefined`; it enables legacy MF2 source
autodetection. Explicit `null` suppresses that autodetection. Normalizing absence
to null would silently select a different editor and is not allowed in the
snapshot.

Dirty drafts are retained in the QueryClient cache, scoped by username, project,
and row, across same-app route changes. Explicit discard or a matching save
clears them. A single unload listener guards retained work even on another app
page, and is removed when that cache is emptied. This is in-memory retention,
not recovery after a crash or a confirmed browser reload. Detail row state is
remounted by user/project/row; this also resets detail context-tab/hero preferences
on row changes while preserving the main list layout and filters.

`useReviewProjectMutations` uses one discriminated action state: `idle`, `pending`,
`validation`, `conflict`, `failed`, or `succeeded`. Existing display controls are
derived from that state. A stopped request, dismissed validation dialog, exhausted
retry, or conflict is never treated as a successful save. A mismatched response
row is an error. Obsolete/unmounted operations cannot acknowledge a new editing
session or replace its cache entry.

HTTP 200 and a matching review row ID are insufficient acknowledgement. Full
translation saves require a complete row response that confirms the source
identity, requested translation fields and decision/current-variant binding.
Target comparison accepts NFC normalization.
State-only responses must retain the checked current variant and confirm the
requested state; a Pending no-op may have no decision entity, and an untranslated
Decided row may bind its baseline or a null variant. A revision must be returned
when the request supplied one. Incomplete or contradictory successes are uncertain
outcomes: preserve the draft, publish no acknowledgement and do not automatically
retry the response as a failed HTTP request. These malformed-response cases are
controlled frontend fault injections, not observed backend emissions.

Before publishing an acknowledgement, cancel outstanding project-detail reads
that could return an older snapshot. Also inspect the row already in the cache:
do not replace a different source, or a revision that matches neither the
submitted revision nor the response revision. Preserve that observed state and
ask the reviewer to refresh/check the saved result. Revision strings are opaque;
this rule compares identities without guessing their chronological order. It
cannot prevent a later writer or an update the client has not yet observed.

Conflict recovery carries the same operation ID and retains the original local
request separately from each recovery attempt. "Use mine" checks the revision
shown in the conflict; another intervening edit must conflict again. Recovery
cannot carry that target onto a changed source. "Use external" always performs a
guarded state-only request, even when the displayed conflict already says Decided.
A second conflict followed by "Use mine"
still submits the original target, comment and notes. Explicit state-only
acceptance binds the decision to the checked current variant. Authoritative
conflict rows update the cache without replacing a dirty draft's base, so Reset
and route restoration see the version shown in the conflict.

The accepted Use external operation ID lets the draft record exactly which
values the reviewer chose to discard. Its acknowledgement merges each field:
unchanged fields adopt the external value, while edits after that click stay in
the draft and prevent advance. This includes comments and decision notes, which
remain enabled while the request is pending. A newer note must not restore the
old translation that the reviewer chose to discard. The discard snapshot is
separate from the original submitted values, preserving correct behavior if Use
external conflicts again and the reviewer then chooses Use mine.

Request callbacks belong to a mounted user/project session. Obsolete callbacks
cannot initiate writes. Row attempts and project metadata requests freeze their
session identity before asynchronous work and recheck it inside the transport
function. Ending a session therefore prevents later retries and queued offline
writes as well as stale callbacks. An already-issued request can still commit;
the client does not claim to cancel or roll back that server operation. Late
metadata responses cannot be merged into the next project's cache. These checks
use the user identity observed by the app; unobserved browser credential changes
are outside this boundary.

## Persistence contract

The current-variant comparison and decision write must run under the same
transactional lock. Existing current rows and the initially untranslated case
both need concurrency coverage. A stale client response is not evidence that a
transaction failed: matching saved state from the same reviewer can reconcile a
retried request, while a different reviewer's result remains a conflict.

The full row response includes an opaque `reviewStateRevision`. Version 2 binds
the review row, underlying TM text unit and locale, current variant, decision
identity/version, and staged suggestion identity/version. A metadata replacement
that remaps the source must change this token and share the parent-TM/current-row
locks with guarded saves. A post-commit statistics callback must not turn an
already committed translation into a failed HTTP response; cold detached graphs
use a scalar repository lookup, while initialized import graphs avoid that query.
New page saves send `expectedReviewStateRevision` from the retained draft base.
This protects notes, decision state, and staged suggestions even when the current
translation ID did not change. The response must include staged suggestions and
other row fields: replacing the query cache with a lossy partial row is unsafe.

The optional revision preserves API compatibility. Older clients that omit it do
not receive the additional protection against stale notes/suggestions. A release
must therefore include client refresh and served-bundle verification; deploying
backend code alone does not complete this work.

## Verification and release criteria

The permanent tests exercise the real page/router and mutation hook for failed
saves, explicit recovery, validation, dirty/clean refresh, save-and-advance,
filter changes, and synthetic IME composition. Separate browser checks exercise
the actual editors and virtual list with controlled responses. Database tests
must demonstrate concurrent writes against local MySQL, including absent current
translations and revision changes without a new translation variant.

Before release, record the exact checks that passed and their limits, inspect the
final diff, and verify the served client/server revisions after an authorized
deployment. Actual OS input methods, long-lived vendor tabs, and the original
production incident remain distinct evidence requirements. No finite test suite
supports a claim that the entire page is free of defects.

## Recorded local results

The September 2 verification passed **781 frontend tests across 75 files**,
**90 targeted backend tests**, formatting/lint checks and a TypeScript/Vite
production build. Integrated browser/Spring/local-MySQL checks covered a
deliberately lost real HTTP 200, exhausted retries, real 409 conflicts, route
restoration and newer comments during conflict recovery. Cold-context database
regressions removed an older test workaround that had masked a post-commit 500.

See the [confidence record](../investigations/2026-09-02-review-project-confidence-verification.md)
for methods, the earlier misleading test boundary and remaining release limits.
Earlier controlled-response results are retained as historical summaries in the
[adversarial record](../investigations/2026-09-01-review-project-adversarial-verification.md).
Original captures remain in ignored local `target/` evidence directories. These
records do not claim production-incident attribution or deployment verification.
