# Translation eval: executable local pilot and next product slice

September 18, 2026. Source inspected at `f6c731cd57` on local `master`.
This report records a dated local experiment with synthetic cases. No production data,
translations, prompts, deployments, or database services were changed by that experiment.

## Why the loop remains incomplete

Mojito has review evidence, but no action that compares a proposed change against the same frozen
cases. `AiTranslateEvaluationService` reports recent historical equality/edit distance and cohorts;
the Learning page tells an operator to edit a prompt and wait for later reviews. Different cohorts
can contain different strings, locales, reviewers, and dates. That discovers problems but cannot
isolate whether a prompt change helped.

Design 029 explicitly deferred replay and prompt drafts. Design 033 proposed a much larger system
covering exports, workers, datasets, calibration, blinded judgments, optimization, and promotion.
The tracker kept that as one broad item. My assessment: we built evidence collection, then left the
small executable comparison step inside a larger platform proposal. There is no requirement to
finish that platform before running useful experiments.

Newer immutable feedback events already supply source/context, raw before/after values, attribution,
reviewer reasons, and problematic status. Reuse them for finding cases. Their pattern thresholds
are conservative suggestion heuristics, not a prerequisite for manually testing a hypothesis.

Source references:

- `webapp/src/main/java/com/box/l10n/mojito/service/oaitranslate/AiTranslateEvaluationService.java`
- `webapp/frontend/src/page/settings/AdminAiTranslateEvaluationsPage.tsx`
- `webapp/src/main/java/com/box/l10n/mojito/service/review/feedback/ReviewFeedbackCaptureService.java`
- `dev-docs/design/029-ai-translation-feedback-evaluation.md`
- `dev-docs/design/033-translation-evaluation-and-learning.md`

## What actually ran

The new [runner](../../dev-tools/translation-eval/README.md) made 20 successful Responses API calls
from this machine: 24 synthetic cases, four locales, two repetitions, 120 outputs. There are only
ten distinct source strings; repeated rows and translations of the same string are correlated.
Inputs and checks were frozen before generation. No LLM judge supplied the reported scores.

The experiment used one provider model with **medium reasoning**; all 20 responses completed
with no request retries or failures. The exact requested and returned model identifiers are
preserved in the ignored local run artifacts. The published figures are dated illustrative
evidence, not a claim about another model or Mojito's deployed configuration.

The runner uses the current literal `TARGET_ONLY_NEW` prompt and corresponding multi-target input
and output shape, with six strings grouped per locale. It is not a replay through the production
Java service: database locale/source rules, screenshots, related-string selection, import checks,
and repair are absent. No real reviewed examples were retrieved.

| Controlled comparison | Before | After | Interpretation |
| --- | ---: | ---: | --- |
| French/German: source only → supplied description/glossary | 16/24 | 24/24 | Four distinct cases improved in both repetitions; explicit meaning and terminology helped |
| Hindi: context → context plus supplied ASCII-period policy | 6/12 | 12/12 | The policy removed request-level punctuation variability |
| Bengali: context → context plus supplied ASCII-period policy | 0/12 | 12/12 | The policy changed danda endings to requested dots |

Counts are candidate outputs satisfying **all narrow requirements for that case**, not rates of
linguistically correct translation. The before arm is intentionally missing information. The
experiment demonstrates value in supplying requirements, not superiority of a newly invented
general prompt or proof that production lacks this information. Danda is not inherently wrong.

Concrete examples in both repetitions:

- French `Free`: `Gratuit` became `Libérer` when told this button releases a reserved room.
- German `Free`: `Kostenlos` became `Freigeben` with the same context.
- French workspace terminology changed from `espace de travail` to the supplied synthetic `atelier`.
- German workspace terminology changed from `Arbeitsbereich` to supplied `Arbeitsraum`.
- `Light` was already `Clair` / `Hell` without the description: no improvement on that case.
- Hindi context-only outputs used dots for all six strings in one request and danda for all six
  in the other. Do not treat those six rows as independent evidence of six different defects.

All protected literal checks passed. A separate post-run agent inspection found no unchanged
English-source echoes; all 72 Hindi/Bengali outputs contained the expected script, and no apparent
French/German lexical-check misclassification was found. This is not bilingual human adjudication.

Provider-reported usage totaled **25,517 tokens** across the 20 experiment calls. Calls grouped six
strings each. Observed median elapsed times were 3.8s for source-only (8 calls), 3.4s for context
(8), and 8.7s for context plus style (4). The last arm contains only Hindi/Bengali; these timings
are diagnostic, not a controlled latency comparison. No monetary cost was inferred from token
counts. Smoke calls are outside the experiment totals.

## What to build in Mojito

Build one vertical slice: **Learning → Compare this change**.

1. **Save 20–50 cases.** Pick one recurring issue in one repository and one or two locales.
   Freeze source, description, intended meaning, required terminology, and generation inputs.
   Keep accepted targets and reviewer explanations as evaluation evidence, not generator input.
   Split the examples used to design the change from untouched cases used to check it.
2. **Enter one draft change and Run.** Initially allow a locale suffix or source-rule draft.
   Run baseline and candidate on the same frozen inputs using the same model and reasoning.
   Set a small case/request budget, preserve failures, and keep every output out of TM.
3. **Inspect the changed cases.** Show deterministic violations and source/context alongside
   blinded A/B candidates. Reuse translator access and offer A better / B better / tie /
   both bad / unsure. Store a short reason and report raw counts, including regressions.
4. **Keep the report.** Let a PM/admin use existing prompt settings to activate a reviewed change.
   Record the chosen report and previous settings for rollback. No automatic promotion.

The first serious backend work is a shared snapshot-in / candidate-out generation boundary, using
current Java prompt assembly and validation without importing output. The local script deliberately
does not pretend to provide that parity. A thin saved-case/run/result surface can then use existing
task and blob infrastructure. Avoid a new worker service, external eval platform, embeddings, GEPA,
automatic prompt rewriting, and model sweeps until this small loop is used on real cases.

Operating cadence: one owner, one small case set, one change per run. Inspect all regressions and
have a bilingual reviewer judge the changed subset plus unchanged controls. Do not require three
reviewers or a pattern-readiness threshold merely to run an experiment. A practical first milestone
is one real recurring defect reduced on untouched cases with no new substantive reviewer-identified
errors. Tiny samples cannot establish universal safety or support a model switch.

## Fix the existing acceptance metric first

The historical Learning query selects all `DECIDED` decisions but does not carry accepted-variant
status or `includedInLocalizedFile`. The service labels exact text equality `exactAccepted`.
The decision save path can persist a `DECIDED` problematic/excluded translation without changing
its text. That path can therefore inflate “accepted unchanged.” This is a source-proven eligibility
gap; no production incidence was measured.

Carry explicit approval/problematic status into the report, display excluded/problematic decisions
separately, and test unchanged-but-problematic cases. New immutable feedback events already retain
those fields. Relevant paths are `AiTranslateTextUnitAttemptRepository` near the evaluation query,
`AiTranslateEvaluationService` near `exactAccepted`, and `ReviewProjectService.saveDecision`.

## Artifacts and verification

The local artifacts are in a dated subdirectory of
`dev-tools/translation-eval/runs/` (Git-ignored): `plan.json`, 20 request-result
files, `summary.json`, `report.md`, and the expandable `report.html`. The runner is repeatable from
the local cases file; saved outputs preserve this exact observation even if a model alias
later moves. The plan records code, dataset, prompt, and request hashes.

Six offline tests cover explicit model selection, nullable descriptions, incomplete responses,
invalid output ID sets, accepted-target rejection, and missing-result accounting. They pass during
release preparation. No frontend/backend application behavior changed in this pilot; its checks
do not establish full application, database, or deployment validation.
