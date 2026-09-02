# Review Project cross-row carryover — September 1, 2026

## Confirmed mechanism

A previous row's AI suggestion could remain actionable after selection changed,
while its handler already edited the newly selected row. Applying the stale
suggestion created a persistent wrong-row draft; ordinary Accept then submitted
that target with the new row's identity and expected variant.

The existing editor remount, duplicate-save guard and selected-row draft checks
did not prevent this path. The backend checked the destination row's version,
but that check could not determine whether the submitted text belonged to its
source. This local reproduction establishes a defect, not the client interaction
behind any reported incident.

## Ownership fix

1. The AI conversation stores its row, locale and reviewed context with its
   messages.
2. Rendering immediately hides messages owned by another context, including the
   interval before reset effects run.
3. Every asynchronous and functional message update retains its originating
   context. Previous request-attempt and abort checks remain in place.
4. A suggestion can update only the editing session for that same context.

The permanent regression is `ReviewProjectAiCarryover.test.tsx`. It exposes the
committed-selection boundary deterministically and exercises the actual
suggestion button and editor. The fixtures use synthetic translation examples.
The regression fails without the ownership fix and passes with it.

## Historical verification

A local browser fixture also reproduced the scheduling boundary without
`flushSync`: select the next row, yield to a microtask, activate the previous
suggestion if still connected, then click the ordinary Accept button. The
unfixed view submitted the old target for the new row; the fixed view disconnected
the stale suggestion. Both plain and assisted editors were checked. A separate
delayed save-and-advance check kept the two rows' payloads separate.

These were scripted browser events with controlled API responses, not recovered
human interactions or real database writes. The initial full suite passed 582
tests, including 40 selection/save matrix cases and four AI ownership cases.
Later probes found additional defects despite that pass; see the
[follow-up](2026-09-01-review-project-follow-up.md),
[state design](../design/031-review-project-editing-state.md) and
[latest verification](2026-09-02-review-project-confidence-verification.md).

## Evidence and limits

The [deployment evidence note](2026-09-01-review-project-deployment-evidence.md)
explains why source ancestry and a freshly served bundle cannot identify the
code running in an earlier browser tab. Request/client evidence was insufficient
to attribute the reported incidents to this local mechanism. REVIEW-07 tracks
bounded, content-free attribution for future reports.

Original captures and reproduction harnesses remain in the ignored local
`target/review-project-investigation/` directory. The original documentation is
also preserved under
`target/review-project-confidence-20260902/checkpoint-prep/original-files/`.
Those artifacts are local evidence, excluded from the public change; this note
contains no production row identities or supplied translation text. Local
verification does not certify a deployment or resolution of an earlier incident.
