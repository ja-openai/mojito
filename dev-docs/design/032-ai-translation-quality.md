# AI Translation and Review Quality

## Intended outcome

Generate faithful, natural translations with the available source and terminology context. Reject
incomplete or misassigned model output before importing it. Use review to identify concrete defects
and unresolved ambiguity, while preserving good translations and existing human approval decisions.

## Generation and validation

1. Compose the translation prompt with locale guidance, matched source rules, source descriptions,
   related strings, screenshots where available, and matched glossary terms. Glossary payloads
   explicitly retain the do-not-translate flag.
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
reasoning. This pipeline uses one model request per generation/review step and does not claim to
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
negation/conditions, quantities, glossary and do-not-translate terms, regional wording, ICU/MF2,
markup/placeholders, and legitimate source expansion. Compare old/new prompts at the same effort,
then compare medium/high/max using the selected prompt. Blind the candidate order for human review.
Track meaning errors, terminology/structure errors, unnecessary rewrites, unresolved ambiguity,
completion/timeout rate, latency, and the actual served tier. Use the existing Learning view for
matched locale/repository follow-up; its observational cohorts are not a randomized comparison.

Use `029-ai-translation-feedback-evaluation.md` for interpretation and promotion boundaries. Additional
model passes or automatic review selection should follow measured defects and reviewer outcomes,
rather than a model's self-reported confidence.
