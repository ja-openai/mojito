# Review edit feedback

## Review flow

Accept records the raw baseline and submitted target in an immutable
`review_feedback_event`, in the same transaction as the review decision. This applies to
ordinary translation reviews and accepted/kept agent proposals. Pending, deferred requests,
failed saves, and replayed agent decisions do not create acceptance events. The existing
mutable review decision and normal editor remain the working review surface.

Reported-issue reviews show an optional feedback area whenever the draft differs from
Original at review, including immediately after Use suggestion and for small edits. A flat
Feedback section uses the same muted heading style as Translation, with no surrounding card
or introductory message. Reason chips and a 500-character note are saved by the existing Accept
action. Switching between the original, suggestion, and manual edits preserves unsaved
feedback. This display comparison is separate from the stored AI-baseline-to-final diff.

Ordinary reviews of known AI translations show this area after a material edit or a
problematic assessment; quote, casing, punctuation, and whitespace edits are recorded
silently. In these feedback flows, the widget replaces the bottom Comment on translation
and Decision notes fields. Ordinary reviews without the new feedback flow retain their
standard fields. Unsent older comments or notes remain recoverable in a compact Unsent notes
disclosure; a note already represented in the widget is not shown twice. Existing agent
notes share the feedback text draft; saved notes/history remain accessible in the report.
Editing only the widget feedback on a completed editable review uses the existing guarded
reopen-and-save operation, retaining the current translation and the earlier review history.
Chat usage and use of a chat suggestion are optional client observations, explicitly labeled
as such; they are not trusted model provenance.

## Evidence and identity

Events retain source/context, repository/request/project/string and variant identities,
reviewer, timestamp, raw final text, stored normalized text, deterministic diff metadata,
optional feedback, and SHA-256 source/baseline/final hashes. The unique event key includes
the decision revision. Agent retries also fingerprint the optional feedback payload.
Application code cannot update events and the repository is not exported through Data REST.
Scalar IDs allow evidence to survive project deletion.

The AI baseline comes from the exact imported attempt linked to the reviewed variant, or
the agent proposal being decided. The latter retains the original translation separately.
Known attempt model, prompt fingerprint, reasoning/verbosity settings and lineage reference
are retained; agent evidence retains producer, run/configuration/method and manifest identity.
Unattributed historical translations are not silently labeled as AI. Model, glossary and
style-guide versions absent from the originating record remain explicitly unknown/null.
The current glossary or prompt must never be substituted for historical generation context.

## Cheap classification

The synchronous path performs no provider, embedding, blob-storage or glossary-wide lookup.
It distinguishes unchanged, normalization, whitespace, quote style, casing, punctuation,
mixed style, protected-token changes, and unknown material edits. Protected-token checks cover
placeholders, ICU delimiters/selectors, HTML, Markdown, URLs and numbers. Token comparison
caps its quadratic LCS work at 65,536 cells and falls back to a labeled changed-span count.
Raw text is retained even when normalized text matches. Length delta is in Unicode code points.

Grammar, meaning, terminology, tone, context and UI-fit reasons are reviewer judgments stored
separately from the automatic classification. Terminology feedback is explicitly
`REVIEWER_REPORTED`; automatic glossary-based classification requires the historical glossary
snapshot and is not inferred from today's glossary. Semantic classification remains
`UNKNOWN_MATERIAL_EDIT` when there is no human reason.

## Asynchronous patterns

The scheduled observation window reads at most 2,000 indexed AI-baseline events once per
minute, outside Accept. It groups by locale/project/model/prompt and directional transform.
Straight-to-curly quotes and the reverse are separate; different wording with the same quote
conversion can share a pattern. A reviewer's latest judgment for the same string/baseline
is used once. Different baseline versions do not count as reviewer disagreement.

Instance significance and pattern significance are separate. A small quote edit remains
low at the instance level but can become repeated evidence. Candidate thresholds are at least
10 distinct strings, 3 reviewers, a 20% correction rate in the cohort's reviewed-string
opportunities, and no competing final translations for the same baseline. These conservative
heuristics produce `READY_FOR_HUMAN_REVIEW`, never an automatic prompt change.

The admin Learning page shows the cached results and up to three examples per pattern via
`GET /api/review-feedback/patterns`. This is a recent observation window, not an all-time
quality metric or a calibrated causal estimate. Cached summaries rebuild after restart;
raw events remain durable. Any later semantic/embedding/LLM analysis or rule generation must
run asynchronously against these events and retain human promotion of prompt changes.

## Validation and rollout

Migration V120 adds the event table and bounded-read indexes. Local checks use mocks and HSQL;
no external database services are needed. Tests cover raw/normalized evidence, directional
quote trends, token bounds, retry payload retention, review integration, and transaction
rollback plus schema/unique-key behavior. Apply the migration with the matching backend before
testing durable event capture. Frontend preview against an older backend establishes layout
only. Production query plans, retention policy, source glossary snapshots, and representative
multilingual calibration remain rollout work.
