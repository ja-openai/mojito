# AI Translation and Review Quality

## Intended outcome

Generate faithful, natural translations with the available source and terminology context. Reject
incomplete or misassigned model output before importing it. Use review to identify concrete defects
and unresolved ambiguity, while preserving good translations and existing human approval decisions.

## Generation and validation

1. Compose the translation prompt with locale guidance, matched source rules, parsed plural guidance,
   source descriptions, related strings, screenshots where available, and matched glossary terms.
   Glossary payloads explicitly retain the do-not-translate flag.
2. Ask for meaning-preserving, idiomatic wording. Source length is not an implicit character limit;
   negation, conditions, quantities, names, and units take priority over brevity. Input context must
   not become invented claims or persuasive additions. Protected ICU/MF2 and markup structure remain
   part of the translation contract.
3. Keep each single-output translation mode to one source per request, even when several sources
   share a screenshot. `TARGET_ONLY_NEW` can group sources because its output carries text-unit IDs.
   Legacy Batch uses a matching one-ID wrapper for that mode.
4. Require complete provider output and the exact expected target membership. Reject missing, extra,
   duplicate, or null IDs and empty targets for nonempty sources before changing candidate DTOs or
   marking a successful lineage response. A `WITH_REVIEW` response must echo the correct source;
   its confidence cannot override that check.
5. Keep existing importer integrity checks, lineage, attribution, and explicitly requested import
   status semantics. The default remains human review needed. These changes introduce no
   confidence-based automatic approval and no unconditional second model call.

The separate OpenAI MT engine also uses configured reasoning/verbosity/speed, checks completion,
rejects unexpected membership and empty output, and preserves its placeholder-encoding path.

### Locale-specific plural guidance

AI Translate parses source messages before requesting translations. For embedded ICU `plural` and
`selectordinal` arguments and supported MF2 numeric selectors, the prompt names the target locale's
ICU plural categories and their count, separately for cardinal and ordinal selection. For example,
Arabic cardinal guidance lists `zero, one, two, few, many, other`. The model must cover all listed
categories while preserving variables, exact-number branches, offsets, and branch conditions.
MF2 keeps its `*` fallback; adding plural categories must preserve the other selector dimensions.

Plain text, string selection, and messages that cannot be parsed do not receive guessed plural
instructions. No-batch requests group only strings with the same plural guidance as well as the
same source-rule matches and screenshot. Legacy Batch adds this guidance to each individual
request line. No-batch generated instructions remain visible in the existing request lineage.

Embedded-message guidance uses the installed ICU data, including current French `many`, through
an explicit message-format lookup. The older database/gettext keyword policy in
`014-icu-cldr-plural-policy.md` remains separate. Both online and legacy Batch requests include
managed locale suffixes (exact locale-tag lookup, without new parent-locale inheritance).

### Candidate validation and one repair attempt

Before changing candidate DTOs or importing them, AI Translate evaluates recognized ICU/MF2 source
and target messages with the existing normalized translation-integrity diagnostic types. This is a
generation policy; repository-specific save/import checks remain in place, and human save rules are
unchanged. Plain text and unrecognized formats do not acquire guessed ICU syntax requirements.

- Definite target syntax, input/selector contract, fallback, exact-case, and supported locale plural
  coverage errors reject the candidate and qualify for one model repair request.
- Source errors reject the candidate without retry. Unsupported/custom/dynamic selection semantics,
  unavailable locale data, and bounded analysis limits produce explicit review findings instead of
  invented coverage claims.
- Missing rendered information is a review warning. A count may be expressed naturally in words in
  a fixed-quantity form, while retaining its selector. Category names alone do not prove a fixed
  quantity; the evaluator uses locale rules and understood numeric semantics. Names, dates, amounts,
  links, and other information do not receive a blanket locale-based exemption.
- Findings on accepted candidates are retained in an AI Translate warning comment. Existing
  repository integrity settings still run during import and may impose further requirements.

Online repair requests contain only the failed string, its previous candidate, and target error
codes/details. They preserve the original source description, glossary, related strings, screenshot,
locale/source-rule/ad hoc instructions, model settings, output schema, and timeout. They have separate
request/response lineage, failure status, and token usage; successful peers are not retranslated.
The repaired response repeats completion, identity, nonempty-target, and MessageFormat checks. A
second failure leaves the existing target, status, comments, and attribution untouched and appears
in the run report. Warnings alone never cause a repair call.

Legacy Batch uses the same preflight and creates one follow-up provider Batch containing only failed
strings. Repair request lines retain their original uploaded request bodies plus diagnostic feedback;
managed settings are not reread during repair. The existing poll job carries the follow-up batch and
its attempt marker. Errors after repair are terminal. Original input IDs also bound response
membership: duplicate or unexpected IDs reject the output before mutation, and missing results are
reported. No synchronous online fallback is introduced into Batch processing.

A permanent blob marker records the repair submission as pending before contacting the provider,
then stores the created batch response. Replaying the original import reuses that response. An
unresolved pending marker stops automatic resubmission and reports the uncertain outcome. The
helper serializes callers in one process; the blob interface has no atomic create operation, so
simultaneous first submissions from separate processes are not guaranteed to run exactly once.
The repair copies the original DTO snapshot into a separate permanent blob and carries its new
key, keeping it available throughout the provider's 24-hour window and later import replay.
Database temporary-blob expiry uses creation time; overwriting the old snapshot would not extend
it, and the default temporary retention can expire before the full Batch processing window ends.

Complete category coverage does not require distinct wording in every branch. Translation-memory
and exported message compression are unchanged; any later optimization must preserve runtime
selection semantics and should be justified by measured file size. Deterministic acceptance does
not establish linguistic quality.

## Reasoning and speed

The default model remains `gpt-5.6-sol`. AI Translate, AI Review, and OpenAI MT use `max` reasoning,
`low` verbosity, and standard (`default`) online processing by default. Review settings are also
shared by glossary AI extraction/review. Deployment properties can override these defaults
independently:

```properties
l10n.ai-translate.responses.reasoning-effort=max
l10n.ai-translate.responses.text-verbosity=low
l10n.ai-translate.responses.service-tier=default
l10n.ai-review.responses.reasoning-effort=max
l10n.ai-review.responses.text-verbosity=low
l10n.ai-review.responses.service-tier=default
```

`fast` and `priority` are API aliases. Deployments with access to `ultrafast` processing for
`gpt-5.6-sol` can set both service-tier properties to `ultrafast` while keeping reasoning at `max`.
This access-controlled tier is an explicit deployment choice. The actual returned `service_tier`
is retained independently of the requested tier and must be checked to confirm the served tier.
Provider Batch uses its own scheduling and omits online tiers. Legacy translation Batch forwards
reasoning for the verified `gpt-5.6-sol` and `gpt-5.6` model names; older model overrides retain their
prior omitted-reasoning behavior. Legacy source-rule partitioning remains a separate backlog item.

Timeouts explicitly support `xhigh` and `max` with configurable multipliers of 8 and 12. They must not
fall back to the `none` multiplier. The adaptive limit remains 300 seconds by default; explicit
AI Translate request timeouts keep their existing semantics. These are engineering budgets, not
promises about model latency. The client extracts only message text, safely skipping reasoning
items that have no message content.

The API has no `reasoning.effort=ultra`. Application Ultra mode adds automatic subagents to maximum
reasoning. Generation can make one bounded structural repair request; review remains a single model step.
This does not claim to
reproduce that orchestration. See the official [model guidance](https://developers.openai.com/api/docs/guides/latest-model)
and [Fast mode contract](https://developers.openai.com/api/docs/guides/fast-mode).

## Review where it adds value

The shared review prompt checks meaning, required terminology, locale grammar, and protected
structure before proposing a correction. A valid target is returned verbatim, including meaningful
surrounding whitespace. Alternatives require a real ambiguity or an explicit request. The rating
rubric distinguishes substantive defects, minor actionable defects, and no identified defect;
no score constitutes approval.

Background, Batch, and single-item fallback reviews receive repository-scoped glossary context and
locale guidance. Requested source-only review modes keep their own input and output schema.
Interactive pages retain their existing warning/glossary context path.

Review Project interactive AI review separates non-breaking-space presence from quality warnings.
NBSP (`U+00A0`) and narrow NBSP (`U+202F`) are sent as neutral character observations with total
counts and up to 20 one-based Unicode code point positions per type in the raw target. The model
must assess placement using the locale, surrounding text, and supplied style guidance, explain a
specific misuse before proposing a correction, and preserve valid non-breaking spaces. Absence
from the source does not by itself make a target space erroneous. Existing boundary-whitespace,
repeated-space, tab, control, and other warnings still reach the model independently.

The same context builder runs for initial review, chat follow-up, and retry using that request's
target. Presence observations require live review, including narrow-NBSP-only targets that
previously could use precomputed review; targets without page context retain the cache path.
This frontend change does not alter saved text, save-time integrity checks, the existing UI
inspection signal, or Hidden chars Auto/All/Off. Neutral UI presentation and deterministic
locale-specific typography checks remain separate design work; no per-locale rule table is added.

Do-not-translate terms retain an approved locale-specific target when one exists; the source is
only the fallback. Responses output must be complete and contain message text before it can become
a suggestion or cached review, even when a partial response contains parseable JSON. Legacy
Chat Completions review imports retain their existing format handling.

New frontend precompute work uses the internal `for-frontend-v2` run name. Old rows remain stored,
old in-flight batches keep their original import namespace, and cache-only lookups never initiate
provider work. This is a policy-version boundary, not continuous invalidation after glossary or
configuration edits. Custom run names are preserved.

## Quality evidence and next decision

The automated regression suite covers output identity, incomplete response handling, source
isolation, do-not-translate metadata, reasoning/speed serialization, deadlines, context propagation,
and cache provenance. These tests do not establish better linguistic quality or prove that review
can be removed.

Before rollout claims, select a fixed set of human-reviewed examples covering ambiguous UI strings,
negation/conditions, quantities, glossary and do-not-translate terms, regional wording, ICU/MF2
plural coverage (including cardinal/ordinal rules, exact-number cases, and MF2 selector combinations),
markup/placeholders, and legitimate source expansion. Compare old/new prompts at the same effort,
then compare medium/high/max using the selected prompt. Blind the candidate order for human review.
Track meaning errors, terminology/structure errors, unnecessary rewrites, unresolved ambiguity,
completion/timeout rate, latency, and the actual served tier. Use the existing Learning view for
matched locale/repository follow-up; its observational cohorts are not a randomized comparison.

Use `029-ai-translation-feedback-evaluation.md` for interpretation and promotion boundaries. Additional
model passes or automatic review selection should follow measured defects and reviewer outcomes,
rather than a model's self-reported confidence.
