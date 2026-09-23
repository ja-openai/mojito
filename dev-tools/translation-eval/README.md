# Local translation comparison

A small executable probe, not a production eval service. Python 3 standard library only;
no database, Docker, Promptfoo installation, or Mojito server required. `run` executes locally
and makes paid calls to the OpenAI Responses API using the existing `OPENAI_API_KEY` environment
variable. It never calls Mojito APIs or imports translations.

From the repository root, replace `MODEL_ID` with a model available to your account that supports
the requested reasoning effort and structured outputs. `plan` and `run` require an explicit
`--model`; there is no default model. The default reasoning effort is `max`; the examples select
`medium` explicitly.

```sh
# Freeze and inspect inputs without calling a model.
python3 dev-tools/translation-eval/run.py plan \
  --model MODEL_ID --effort medium --out /tmp/mojito-eval-first

# Run the frozen comparison (20 requests with the supplied dataset and default repetitions).
python3 dev-tools/translation-eval/run.py run \
  --model MODEL_ID --effort medium --out /tmp/mojito-eval-first

# Rebuild the report without provider calls.
python3 dev-tools/translation-eval/run.py summarize --out /tmp/mojito-eval-first

# Check result accounting without provider calls.
python3 -m unittest discover -s dev-tools/translation-eval -p 'test_*.py'
```

Open `report.html` to inspect each source and the candidates, or read `report.md` / `summary.json`.
The HTML comparison is labeled for diagnosis, not blinded human preference collection.
`plan.json` contains the frozen cases, literal base prompt, full requests, code revision, and hashes.
One JSON file per request retains the provider response, returned model, usage, timing, and outcome.
Keep these files together. A selected model alias may change; saved outputs are a dated observation.
The September 18 pilot used medium reasoning. Its exact requested and returned model identifiers
remain in the ignored local run artifacts; the published investigation is illustrative evidence,
not a reproducibility claim for a different model.

Use a new output directory when changing cases/settings. An identical rerun reuses existing files,
including failures, and runs only missing jobs. `summarize` retains missing/incomplete/invalid
requests in the denominator. There are at most two concurrent requests, three HTTP attempts per
request, and 30 logical requests. Defaults allow 4,096 output tokens per request: 81,920 output
tokens for 20 calls before retries, plus input tokens. Actual pilot usage was much lower. Timeout
errors are not retried because provider completion may be unknown. No credentials are written.

## What this tests

`cases.json` has 24 deliberately synthetic source/locale cases, ten distinct English strings,
four locales, and no historical accepted targets. Two repetitions produce 120 candidate outputs.
The criteria and three conditions were fixed before generation:

- `source_only`: current `TARGET_ONLY_NEW` instructions with descriptions and glossary removed.
- `context`: the same prompt with descriptions and glossary supplied.
- `context_style`: the same context plus the dataset's explicit Hindi/Bengali punctuation policy.

Source-only is an ablation of missing inputs, not a claim about current deployed Mojito inputs.
The punctuation rule is an explicit product policy for the experiment, not a language-wide rule.
The runner extracts the literal prompt from Java and mirrors the multi-target input/output shape.
It does **not** call the production Java generation pipeline, fetch live locale/source rules,
attach screenshots, select related strings, run ICU/MF2 or importer validation, or perform repair.
These cases contain no plural/select messages. `store=false`, a token cap, and explicit tier are
runner settings; the full production request is not claimed identical.

Checks measure a narrow intended sense, specified terminology, required literals, and terminal
punctuation. They are not a linguistic judge. Untranslated English could pass some structural
checks, and valid synonyms could fail a lexical check. Inspect outputs and have bilingual reviewers
judge meaningful changes. The completed pilot separately checked source echoes and Indic scripts;
those diagnostics were not retroactively added to the frozen score. Do not turn the displayed
pass rate into a general translation-quality score.

For a real pilot, add source/context examples from actual reviewed work, keep discovery examples
separate from untouched validation examples, and retain independent human judgments. This harness
deliberately rejects `existingTarget` and populated `relatedStrings` to avoid easy answer leakage.
Reviewed targets stay outside generation input. Do not repurpose it for historical replay without
auditing the entire input, including glossary, descriptions, and neighboring strings.

See the [measured investigation](../../dev-docs/investigations/2026-09-18-translation-eval-pilot.md)
for results and the proposed small Mojito feature. API output formatting follows the
[official Structured Outputs documentation](https://developers.openai.com/api/docs/guides/structured-outputs).
