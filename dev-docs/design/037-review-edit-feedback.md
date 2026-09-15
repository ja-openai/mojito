# Review edit feedback

## Review flow

Accept records the raw baseline and submitted target in an immutable
`review_feedback_event`, in the same transaction as the review decision. This applies to
ordinary translation reviews and accepted/kept agent proposals. Pending, deferred requests,
failed saves, and replayed agent decisions do not create acceptance events. The existing
mutable review decision and normal editor remain the working review surface.

Reported-issue reviews show an optional feedback area whenever the draft differs from
Original at review, including immediately after Use suggestion and for small edits. A flat
Reason for change section uses the same muted heading style as Translation, with no surrounding
card or introductory message. The note explains the before/after comparison: what was wrong,
why the correction helps, or both. Its placeholder is "Explain the issue or your correction
(optional)". Reason chips and a 500-character note are saved by the existing Accept
action. Switching between the original, suggestion, and manual edits preserves unsaved
feedback. The translator is responsible for updating or removing the explanation before
Accept; changing the target never clears it automatically. This display comparison is separate from the stored AI-baseline-to-final diff.
Once shown, the section stays visible for the selected string, including after reverting an
edit, clearing feedback, or accepting. Selecting another string starts fresh. Its first
appearance has a brief fade when reduced motion is not requested; subsequent edits do not
remove and reinsert it above the chat. Visibility alone does not mark the draft as changed.
Returning to the original disables correction feedback while retaining its draft. Accepting
that original omits the paused reason/note and does not erase them on acknowledgement; they
become editable again when editing resumes. Paused feedback protects against accidental
navigation loss but cannot enable another save by itself. Explicit problematic assessments
still allow feedback on unchanged text, and explanations entered in the Report tab can
justify keeping the original. Submitted assessments acknowledge independently of a paused
correction note. Drafts update as the reviewer types; there is one draft per review, not
per-edit history or a save-on-blur mechanism.
Translation warnings show a compact count and the first message in the existing editor
controls, sharing a reserved slot with saving progress. The message stays on one line and
truncates when space is limited; hover shows the full text and clicking opens the existing
details dialog. Adding or clearing warnings does not add a row above feedback/chat.

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

## Workbench

Workbench's inline editor and text-unit Details view reuse the same feedback widget. For a
known AI baseline, it appears after a material edit or, in Details, a rejected assessment.
Reason and note remain optional and save through the existing Accept/Save action. Returning
to the original text disables correction feedback and preserves the draft; Cancel/Reset
clears it. Paused feedback is excluded from the save payload and save eligibility, but
continues to protect against accidental discard. Once shown, the
section remains visible for that string while the editor stays open, including after Reset.
Feedback participates
in the existing dirty-draft behavior, and validation and save use a frozen payload. Independent
target-comment saves and bulk status actions do not submit widget feedback.

The editor captures the exact immutable text-unit variant when editing starts. A permission-
checked baseline endpoint resolves its AI provenance. Saves with feedback metadata lock the
text unit before its current translation, matching Review Projects and incident intake,
reject a changed baseline, and record evidence in the existing event
table in the same transaction. A reviewer-scoped operation ID and full request fingerprint
make an identical retry return its saved receipt without reapplying an older translation.
The ordinary save contract remains available to callers without feedback metadata.

Workbench events have `surface: WORKBENCH`, a repository identity, and no review-project ID.
They retain raw edits and optional feedback for pending statuses, but only approved or
explicitly excluded/problematic translations count as completed reviews in Learning.

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
minute, outside Accept. It groups by locale/project/model/prompt and directional transform;
Workbench cohorts use repository identity in place of a review project. Incomplete Workbench
saves are excluded from both judgments and reviewed-string opportunities.
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

Migration V120 adds the event table and bounded-read indexes, with a nullable project ID
for Workbench events. Local checks use mocks and HSQL;
no external database services are needed. Tests cover raw/normalized evidence, directional
quote trends, token bounds, retry payload retention, review integration, and transaction
rollback plus schema/unique-key behavior, exact-variant ownership, and Workbench retry/draft
retention. Apply the migration with the matching backend before
testing durable event capture. Frontend preview against an older backend establishes layout
only. Production query plans, retention policy, source glossary snapshots, and representative
multilingual calibration remain rollout work.
