# Translation-integrity conformance corpus

This directory contains a language-neutral behavioral contract for validating
source and target messages. This kind of dataset is called a **conformance
corpus**. It may also be called a golden test corpus, but "conformance corpus"
is more precise here because independent implementations must consume the same
inputs and produce the same normalized results.

[`manifest.json`](manifest.json) is the executable contract and
[`corpus.schema.json`](corpus.schema.json) is its versioned shape. The corpus
contains only synthetic text, private-use locale tags, and reserved `.invalid`
domains. It contains no company, repository, product, customer, or production
translation data.

[`android-generated-resources.json`](android-generated-resources.json) records
the combined Android integrity expectations, including resource syntax, printf
placeholders, locale-specific plural-category presence, intentional plural-count
omission, and Markdown destinations. Android resource syntax cases belong to the
format-owned output adapter; printf, plural, and Markdown-link cases belong to
text-unit adapters at translation mutation boundaries. Consumers may implement
those layers independently and should not require one generic generated-asset
validator to own both concerns.

The manifest-level `diagnosticRules` map gives every stable diagnostic code one
owning rule. A case may emit a diagnostic only when that rule is declared, so
adapters do not need a language-specific ownership table.

`schemaVersion` versions the wire shape. `corpusVersion` versions the behavioral
expectations, so consumers can pin an exact release while adopting newer cases.

Run the dependency-free contract verifier with:

```sh
python3 translation-integrity/conformance/verify.py
```

## FormatJS reference oracle

[`formatjs_parser_expectations.json`](formatjs_parser_expectations.json) pins
the runtime-specific raw error kinds, the two intentional maximum-depth policy
differences, and the two raw-parser differences caused by the portable opaque
tag adapter for `@formatjs/icu-messageformat-parser` 3.5.10. It refers to case
IDs in `manifest.json`; it does not copy the portable messages or their
normalized diagnostics.

Run the real JavaScript parser oracle with the repository's already-installed
frontend dependencies:

```sh
node translation-integrity/conformance/formatjs_parser_oracle.mjs
```

The oracle verifies the package-lock and installed package versions before it
parses every FormatJS source and target without adapter preprocessing. It checks
declared acceptance, raw FormatJS error kinds, Unicode code-point diagnostic
ranges, source dominance, the measured depth of the two inputs that upstream
accepts but Mojito caps at 100, and exact metadata for intentional adapter
differences. It uses `ignoreTag: true` because the corpus assigns rich-tag
structure to a separate rule.

The Java validation parser and its tests consume the same manifest and
expectation file. The Java port is intentionally locale-neutral and does not
include a renderer or JavaScript `Intl.Locale`-dependent `j` skeleton
resolution. Adding the parser does not by itself replace a production
integrity checker; that requires adapter parity plus shadow or canary evidence.

The neutral Java evaluators currently cover four reusable structural slices.
The rich-text-tag evaluator is placeholder-grammar neutral and is exercised in
isolation across 17 non-syntax-dominated `cutover` cases spanning FormatJS,
dollar-template, and double-brace profiles. The FormatJS evaluator composes
message syntax, argument membership, application-controlled select structure,
the explicitly enabled rich-text-tag feature, and boundary whitespace across
all 64 `cutover` cases whose rule sets it owns. The dollar-template evaluator
similarly composes its placeholder contract with the explicit tag and boundary
features across eight `cutover` cases.

The profile-neutral boundary-whitespace evaluator is exercised directly across
eight applicable cases. It scans by Unicode code point with Python's explicit
29-code-point `strip` predicate: U+0009–000D, U+001C–001F, U+0020, U+0085,
U+00A0, U+1680, U+2000–200A, U+2028–2029, U+202F, U+205F, and U+3000. A safe
repair copies the source boundaries around the exact target core only when both
stripped cores are nonempty. The evaluator then reruns the complete selected
structural contract with repair disabled and requires a fixed-point pass.
Independent policy diagnostics and review routing are preserved; any
nonrepairable structural finding suppresses partial repair. Combined boundary
and apostrophe repair remains an `extended` contract and is not implemented by
these composites.

The rich-text-tag cutover gate intentionally matches the downstream Python
checker: it compares exact sets of raw tokens found by `<.*?>`, with matching
stopping at the first `>` and only `\n` excluded from dot. A set mismatch is
normalized into missing, extra, or unbalanced diagnostics, but classification
does not widen rejection. Repeated equal tokens and misnested equal sets remain
`extended` behavior until shadow evidence supports stricter enforcement.

The dollar-template cutover scanner intentionally matches Python
`string.Template.get_identifiers()`: `$name` and `${name}` use the default
ASCII identifier grammar, `$$` is escaped, and malformed dollar tokens
contribute no identifier. This preserves existing true-positive behavior
without introducing a new cutover rejection. Strict invalid-placeholder
diagnostics remain `extended`; applying source-first syntax dominance to this
profile is deferred until shadow evidence shows that enforcing it will not
reject valid catalog data.

None of these evaluators is registered on a Mojito repository or wired to
enforcement; the remaining rule adapters and shadow evidence are still
required before downstream checker retirement.

The shared Java result model represents the complete manifest envelope,
including policy diagnostics, review routing, exemptions, and deterministic
safe repairs. The boundary composite preserves independent policy and review
output and emits the deterministic boundary repair; later policy adapters still
own waiver evaluation.

## What the contract separates

Each case keeps four concerns distinct:

1. **Detector diagnostics** describe every structural problem found in the
   source/target pair.
2. **Policy diagnostics** describe waiver or external-review decisions without
   hiding detector findings.
3. **Disposition** says whether structural validation passes, can be repaired,
   must be rejected, or is exempt.
4. **Safe repair** names a deterministic operation and its exact expected
   result; arbitrary replacement text is not an automatic repair.

Storage status, export fallback, publication, and queue routing are deliberately
not part of this core contract. For example, a save endpoint may reject an
invalid candidate while preserving its previous value, whereas an exporter may
omit the same invalid target. Those workflow choices need separate adapter or
end-to-end tests.

The corpus has two tiers:

- `cutover`: the minimum behavior that an adapter must implement before a
  corresponding downstream checker can be considered for retirement.
- `extended`: stricter behavior for known gaps, including complete literal
  parsing, typed arguments, malformed non-ICU templates, richer tag checks, and
  repair composition that corrects a legacy last-write-wins bug.

Passing the tier alone is not retirement evidence. A consumer must also map
each legacy check to case IDs, prove adapter parity, and observe the new path in
shadow or canary operation before disabling that check.

## Case shape

Each case declares a placeholder grammar, optional renderer features, the rules
to execute, synthetic source and target messages, and the complete normalized
expectation:

```json
{
  "id": "formatjs.argument-missing.reject",
  "description": "A target cannot drop a runtime argument.",
  "tier": "cutover",
  "profile": "formatjs",
  "rules": ["argument-contract", "message-syntax"],
  "source": {"locale": "x-source", "text": "Hello {name}"},
  "target": {"locale": "x-target", "text": "SALUTATION"},
  "expected": {
    "diagnostics": [
      {
        "code": "variable-missing",
        "severity": "error",
        "subject": "target",
        "details": {"names": ["name"]}
      }
    ],
    "disposition": "REJECT_TARGET"
  }
}
```

`diagnostics` is the complete detector result, not merely the first failure.
`policyDiagnostics` is a separate complete list when policy participates.
Diagnostic `details` identify the arguments, tags, selectors, literals, or
policy scope involved. An optional `range` is a zero-based, half-open
`[start, end)` span using Unicode code-point offsets in the diagnostic's
`subject` text, as declared by the top-level `offsetEncoding` field. `start` is
inclusive and `end` is exclusive.

Manifest ordering is canonical for readable diffs. Adapters may discover
diagnostics in any order, but they must normalize and sort before comparison.
The diagnostic sort key, in order, is `code`, `subject`, `severity`, canonical
JSON of `details`, and canonical JSON of `range`; an absent `range` is the JSON
literal `null`. Tuple components are compared lexicographically by Unicode code
point, without locale collation, case folding, or normalization. Here canonical
JSON means recursively sorted object keys, preserved array order, no
insignificant whitespace, unescaped non-ASCII characters, and ordinary JSON
lowercase booleans/`null` and base-10 integers. Strings escape only characters
required by JSON. This is equivalent to Python
`json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))`.
Source syntax is parsed first. A `source-format-invalid` diagnostic suppresses
all other detector findings for the pair and derives `REJECT_SOURCE`. If the
source parses but the target does not, `target-format-invalid` suppresses all
other detector findings and derives `REJECT_TARGET`. Independent policy
findings remain in the policy lane.

Both `source` and `target` are always present. Catalog inventory, missing keys,
and stale generated entries belong to consumer workflow tests rather than this
pair-validation corpus.

## Diagnostic normalization and dominance

The schema fixes each code's severity, subject, and allowed `details` shape.
Adapters normalize implementation-specific exceptions into that portable form:

- one grouped legacy error expands into every independent diagnostic code that
  applies;
- repeated apostrophe findings produce one diagnostic per occurrence;
- membership deltas use sorted `values` or `tags`, while multiplicity deltas
  include exact expected and actual counts;
- names, literals, selectors, options, tags, and waiver-scope fields are sorted
  by Unicode code point and are never case-folded or Unicode-normalized.

Dominance prevents one malformed construct from producing misleading dependent
noise. Source syntax failure dominates the detector lane; otherwise target
syntax failure does. A changed select argument type suppresses option and
occurrence deltas for that argument. Misnested or unbalanced tag structure
suppresses ordinary missing/extra diagnostics for the same structure. If the
structure remains comparable, all independent deltas are reported.

The two maximum-depth cases declare `maxNestingDepth: 100`. That is a parser
safety policy retained for cutover compatibility even if a particular runtime
parser accepts deeper valid input.

## Profiles, features, and rules

Profiles describe placeholder grammar only:

- `plain`: no placeholder grammar.
- `formatjs`: ICU MessageFormat arguments, plurals, ordinals, and selects.
- `dollar-template`: `$name`, `${name}`, and `$$` escaping.
- `double-brace`: `{{ name }}` placeholders.

Renderer behavior composes independently through features:

- `rich-text-tags`: paired rich-text tags are part of the runtime contract.
- `formatjs-apostrophe-escaping`: an ASCII apostrophe immediately before a
  FormatJS opening or closing tag token is unsafe for the configured renderer.

For `formatjs` messages with `rich-text-tags`, ICU argument parsing and rich-tag
validation remain separate. A default-off parser option lets the composite
adapter recognize and consume the downstream Python parser's tag spans only
from message contexts. Consuming the original span atomically keeps attribute
braces, apostrophes, pound signs, and angle brackets opaque while retaining the
original source locations. Raw/default FormatJS behavior remains unchanged, and
the oracle records its intentional differences. The separate cutover rich-tag
rule compares exact sets of distinct raw tokens; stricter balance, nesting, and
multiplicity checks remain `extended`.
The apostrophe rule is ICU quote-state aware. It distinguishes a quote-closing
apostrophe immediately before a tag from a new quote opener, treats `#` as ICU
syntax only inside plural/selectordinal branches, and ignores attribute quotes
inside complete tag tokens. Entering a nested `select` branch resets plural `#`
semantics; returning to the containing plural branch restores them.

This separation allows two consumers to share a placeholder grammar while
having different tag or apostrophe behavior. A case lists only rules that are
applicable to its declared profile and features; unsupported combinations are
contract errors, not successful `NOT_APPLICABLE` results.

## Dispositions

| Disposition | Meaning |
| --- | --- |
| `PASS` | The source and target satisfy all declared rules. |
| `AUTO_REPAIR_TARGET` | Every error has a declared deterministic target repair. |
| `REJECT_TARGET` | The target violates the runtime contract. |
| `REJECT_SOURCE` | The authored source is invalid; the target is not blamed. |
| `EXEMPT` | A current, exact waiver changes policy without hiding detection. |

A rejection does not prescribe deleting stored data or publishing a fallback.
The consuming operation owns that decision.

An optional, independent `reviewDisposition: REVIEW_REQUIRED` represents an
external semantic finding. Keeping it separate allows repair, rejection, or a
structural exemption to coexist with a human-review requirement.

## Safe repairs

Version 1 permits only named transformations whose output can be derived from
the input:

- `COPY_SOURCE_BOUNDARY_WHITESPACE`
- `DOUBLE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG`
- `REPLACE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG_WITH_U2019`

Every repair case contains the exact `expectedTarget` and exact
`expectedDiagnostics` after repair. The verifier derives the output itself,
checks the expectation, and checks idempotence. Boundary-whitespace repair may
compose with either apostrophe strategy, but the two apostrophe strategies are
mutually exclusive.

`expectedPolicyDiagnostics` is empty by default. A repair-plus-review case
lists it explicitly to prove that repairing the target does not clear an
independent policy finding or review route.

Schema version 1 forbids combining automatic repair with a target-hash waiver.
A repair changes that fingerprint, so preserving the pre-repair waiver result
would be stale while re-evaluating it could activate a previously waived error.
That workflow requires an explicit future contract for waiver migration.

Missing arguments, changed literals, malformed messages, changed selectors,
and linguistic findings are never machine-repaired by this corpus.

The two apostrophe operations are explicit policy choices. `icu-double`
preserves the rendered ASCII apostrophe with the native ICU escape.
`compatibility-u2019` matches a legacy downstream normalization while that
checker remains in the path. Both repaired forms pass structural validation;
changing the rollout strategy is a policy change, not an implicit adapter
choice.

An apostrophe repair is allowed only when each detected quote opener remains
unclosed through the end of the message. If a later apostrophe closes that
quote, replacing the first one could make the later apostrophe a new opener.
That ambiguous form is rejected for manual correction instead of being
reported as safely repairable.

## Waivers and external findings

Detector findings remain visible even when policy changes their disposition.
A matching waiver therefore contains the original detector diagnostic in
`diagnostics` and a `check-waived` entry in `policyDiagnostics`.

Waivers are arrays and are scoped by rule, message ID, grammar profile, target
locale, source hash, target hash, owner, reason, and expiry date. The verifier
evaluates expiry against the fixed `policyEvaluationDate` in the manifest, so
the same corpus produces deterministic results. A changed source or target,
wrong rule, wrong profile or locale, or expired waiver exposes the underlying
finding plus a policy mismatch diagnostic.

`sourceSha256` and `targetSha256` are lowercase SHA-256 digests of the exact
post-JSON-decoding `text` value encoded as UTF-8. Do not include the locale,
JSON quoting, a BOM, or an added newline, and do not case-fold or Unicode-
normalize the text. The non-ASCII fixture makes this byte contract executable.
A waiver is current exactly when `policyEvaluationDate < expiresOn`; it is
expired when those dates are equal.

Waiver evaluation is ordered. First compare every scope field, including both
text hashes. If any differ, emit `waiver-scope-mismatch` with the complete
sorted mismatch list and do not evaluate that waiver's expiry. Otherwise emit
`waiver-expired` when `policyEvaluationDate >= expiresOn`; only a scope match
with a future expiry contributes to `check-waived` and the exemption count.

Version 1 allows waiving only the boundary-whitespace rule. Syntax errors and
runtime-contract changes such as missing arguments are deliberately
non-waivable. One combined case proves that a valid waiver does not
short-circuit unrelated diagnostics.

External semantic evaluation is another policy input. The corpus verifies that
an externally supplied finding is preserved and routed through the independent
`reviewDisposition`; it does not pretend that structural validation can
discover or repair meaning. Compound cases cover repair plus review, rejection
plus review, and structural exemption plus review.

Schema version 1 forbids `range` on policy diagnostics, including external
semantic findings. A target repair can shift code-point offsets; preserving a
pre-repair range would be stale, while rebasing it requires a future explicit
contract. Detector diagnostics may still carry the half-open ranges described
above.

## Batch scenarios

`batchScenarios` exercise properties that a single source/target pair cannot:

- validation continues after either a source or target error;
- an automatic repair changes only its declared target and not a neighboring
  case;
- repairs and rejections are reported independently in a mixed batch.

Each scenario names all visited, repaired, target-rejected, and source-rejected
case IDs, plus every review-routed case. It has a deterministic `COMPLETED` or
`COMPLETED_WITH_REJECTIONS` status. A reported repair describes the derived
output only; it does not require a consumer to commit that output when another
entry rejects.

Storage, publication, translation-memory status, and generated-artifact
behavior should be covered by additional workflow scenarios owned by each
consumer.

## Adapter requirements

Every implementation should:

1. Load `manifest.json` directly; do not copy fixtures into language-specific
   tests.
2. Execute exactly the declared rules under the declared profile and features.
3. Normalize implementation errors to the stable schema diagnostics.
4. Compare complete detector diagnostics, policy diagnostics, and disposition.
5. Derive only the named `safeRepair` operations, then revalidate the result.
6. Treat diagnostic ranges as Unicode code-point offsets.
7. Prove repeated validation and repair are idempotent.
8. Run the batch scenarios and prove unrelated entries remain unchanged.
9. Maintain a machine-readable mapping from each retired legacy check to the
   cases that replace it.

Independent Java, Python, JavaScript, Rust, or other adapters should all read
the same manifest. Runtime-specific parser oracles may supplement the corpus,
but they must not silently redefine its normalized outcomes.

## Deliberate non-goals

The core corpus does not determine whether prose is fluent, culturally
appropriate, legally approved, or terminologically preferred. Those questions
require language-specific evaluation datasets and qualified reviewers.

The external-review cases include capability-presence, state-meaning,
accessibility-action, and option-label regressions. They deliberately require
review evidence instead of presenting a structurally valid but incorrect
translation as something a placeholder or markup parser can infer.

Product-trigger exclusions, extraction directives, catalog inventory,
generated-file digests, storage mutations, release fallback, and bundle
publication are consumer policies. They should be tested next to the adapter or
workflow that owns them, not encoded as universal translation-integrity rules.
