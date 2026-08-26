# AI Translation Feedback Evaluation

## Why this lives in Mojito

Mojito already has the two pieces an evaluation needs:

- `ai_translate_text_unit_attempt` identifies the model output imported for a text unit.
- Review Project decisions retain both the variant that was reviewed and the variant that the
  reviewer accepted.

The first useful evaluation should join those records rather than create a separate dataset,
annotation workflow, or external service. Review work that already happens becomes the label.

## What the first slice does

The AI translation prompt settings page has a **Learning** tab. It is admin-only and read-only.
It shows:

- reviewed AI translations accepted without an edit;
- reviewed AI translations that were edited before acceptance;
- normalized character edit distance;
- the source, AI target, accepted target, and reviewer decision notes;
- cohorts grouped by prompt fingerprint, model, reasoning effort, text verbosity, and locale.

The page summarizes the latest 500 matching review decisions. It is an operational feedback window,
not an all-time aggregate. The API can filter by repository, locale, and model and can return up to
1,000 examples when a narrower investigation needs a different slice.

Future no-batch AI Translate attempts store a SHA-256 fingerprint of the complete instruction
prompt plus the model request's reasoning and verbosity settings. The prompt itself remains in the
existing redacted lineage request blob; the relational row stores only the fingerprint needed for
grouping. Older attempts can still contribute examples but appear in an `unknown` prompt cohort.

The evaluation query only uses a review decision when:

- the decision is `DECIDED`;
- `reviewed_variant_id` is the exact variant linked to the AI Translate attempt;
- `variant_id` is the accepted decision variant;
- the Review Project text unit and locale match the attempt.

This avoids guessing from the current TM value, which may have changed after review.

## Interpreting the metrics

`Accepted unchanged` is a strong positive signal: the human accepted the exact AI output. `Edited`
is a useful error-discovery signal, but it does not by itself explain the cause. An edit may point
to a prompt problem, missing source context, a glossary problem, a product-specific preference, a
source-authoring problem, or a model limitation.

Do not rank prompt/model cohorts from tiny or compositionally different samples. Inspect locale and
repository slices, read the examples, and require a useful sample before treating a rate change as
evidence. The dashboard is observational, not a randomized experiment.

## Prompt-tuning loop

1. Start with edited examples and cluster a repeated, prompt-fixable error.
2. Check whether glossary, source description, integrity checks, or source authoring is the better
   fix.
3. Make the narrowest locale prompt or regex-triggered source-rule change in staging.
4. Verify the lineage request contains the intended prompt and creates a new fingerprint.
5. Let normal human review produce labels for the new cohort.
6. Compare the same locale/repository/error slice against the previous cohort.
7. Keep, revise, or revert the prompt based on reviewed evidence.

Production prompts are never rewritten or promoted automatically. A model can propose a prompt
change later, but it must remain a draft until a human reviews the examples and an evaluation gate
passes.

## Why no external eval system yet

An external system is unnecessary for this loop and would duplicate source, lineage, permissions,
and reviewer labels. Add one only if Mojito needs capabilities that are materially awkward in-app,
such as cross-product benchmark ownership, large parallel model sweeps, or a company-wide CI gate.
Even then, Mojito should remain the source of reviewed examples and import only run results.

## Deliberately deferred

- Draft prompt versions with explicit promotion and rollback.
- Offline replay of a fixed, stratified holdout set before staging rollout.
- Blind side-by-side human comparison of candidate outputs.
- LLM-as-judge scoring. Human review remains the primary label; a judge must be calibrated against
  it before use.
- Automatic prompt proposals from edit clusters.
- Statistical confidence intervals and minimum-sample promotion rules.

These should be added only after operators use the evidence page and we know which workflow removes
real review work.
