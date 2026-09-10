# MF2 Conformance and Differential Coverage Audit

Audited again on 2026-09-08/09 with public-API adversarial probes, production
registry bridges, package consumers and cross-runtime regressions. Historical
differential findings below remain useful, but only the current gates and
case-specific dispositions establish current coverage.

Normative claims below refer to the Unicode
[MessageFormat specification](https://www.unicode.org/reports/tr35/tr35-messageFormat.html)
and the vendored official testdata under `third_party/message-format-wg`.

This audit distinguishes parser/model conformance, runtime behavior, platform
adapter behavior, and external-reference comparisons. Passing one layer does
not imply that another layer was exercised.

## Fixture Admission Policy

A language-specific difference is not automatically a shared fixture. A
candidate must record:

1. the MF2 source or model, locale, typed arguments, options, and error mode;
2. the Mojito layer and actual result;
3. ICU4J and ICU4C results, or the exact unsupported reason;
4. the official test or TR35 rule that establishes the behavior;
5. a disposition: Mojito defect, intentional policy, ICU preview limitation,
   locale-data drift, comparison-layer mismatch, or unresolved;
6. the affected runtimes and smallest public regression case.

Only confirmed Mojito defects become shared conformance cases. Exceptionally
important parser/model or safety invariants may be shared when an ICU output
comparison is not meaningful. Reference limitations and locale drift are
documented once rather than copied into per-language snapshots.

## Current Inventory

| Suite | Cases | Runtimes | What is asserted |
| --- | ---: | --- | --- |
| Shared source fixtures | 80 models; 877 output; 12 parts; 7 fallback; 1 fallback-parts; 28 invalid-source; 33 format-error; 10 locale-key | Rust, Swift, Python, Java, Kotlin, JavaScript, Go, PHP | Parsed interchange model, resolved string output, structured parts, fallback values/parts and errors, parser diagnostics, runtime error codes, locale lookup |
| Official Unicode MessageFormat WG data | 462 tests in 16 files; 761 assertions per registry run | All eight via production bridges | Exact 462 error lists, 259 outputs, 20 parts results and 20 parts error lists. Missing responses/crashes/deadlines fail; standard functions are production implementations. Individual API/capability differences are explicit non-passes. |
| Generated all-locale plural fixtures | Included in the 877 shared output cases | All eight shared runners | Public parse-and-format output for every generated CLDR cardinal and ordinal locale/category; generation uses ICU4J category results |
| Selection-operand and resolved-value differential fixtures | 37 common-source selection cases, 5 ICU4J-specific selection cases (4 percent, 1 offset provenance), 4 adapter declaration-chain/offset cases, 1 common currency-provenance case, and 1 adapter currency-override case | ICU4J gates 43 selection/resolved-value cases and separately agrees with the adapter override; optional ICU4C passes 36 common selection cases with 1 expected unsupported and records the currency case as a second expected unsupported; all seven platform registries now load the same 47-case corpus; six pass all 47 and Rust records one unsupported currency case | Final branch/output through public formatting: all plural categories, integer/decimal operands, visible fractions, negatives, zero, grouped millions, exact keys and precedence, the canonical-integer subset plus one explicit Mojito decimal policy, inherited/filtered options, semantic-value provenance through number/integer/percent/offset chains, integer truncation, percent scaling, offsets, and the currency-type barrier versus explicit replacement |
| Invalid-key recovery differential | 1 output-only ICU row plus 1 shared fallback case | ICU4J, ICU4C, all eight Mojito runtimes | ICU proves continuation to the later valid exact variant; shared conformance additionally requires exactly `bad-variant-key` |
| Platform adapter tests and demos | Runtime-specific | Java JDK/ICU4J, Kotlin JDK/ICU4J, Python/Babel, JavaScript/Intl, PHP/Intl, Swift Foundation, Rust ICU4X | Host-backed number, integer, percent, currency, date, time, datetime, timezone, and relative-time behavior where implemented |
| Language unit/package tests | Runtime-specific | All implementations | Package boundaries, parser and formatter invariants, callbacks, registry behavior, code generation, and runtime-specific edge cases |

### Direct official results and capability boundaries

| Registry | Passed assertions / evaluated | Recorded differences |
| --- | ---: | --- |
| All eight portable registries | 717 / 761 | 11 parts API differences; 32 unsupported currency/date/time/datetime error assertions; 1 intentionally unlocalized French number output |
| Python Babel, JS Intl, Java JDK, Kotlin JDK, Swift Foundation, PHP Intl | 750 / 761 | 11 parts API differences |
| Rust ICU4X | 738 / 761 | Same 11 parts differences plus 12 unsupported currency assertions |
| Go platform | Not implemented | No platform conformance claim |

Every disposition pins the original merged upstream case hash and exact actual
result. A changed failure, missing assertion, stale exception or unexpected pass
fails. The public parts API retains expression strings and original model
metadata; it does not expose upstream resolved numeric subparts, resolved markup
options, `u:id`, locale or standalone bidi-isolation parts. Those differences
are evaluated rather than normalized into fictitious passes.

The legacy `unicode-tests` pass/skip baselines count exercised cases and retain
historical test-registry behavior. They must not be cited as proof that every
upstream assertion or production platform function passed.

The shared gate also runs **413 deterministic scanner mutations** around the
42-character parser nontermination input, with a 20-second batch deadline and
process-group cleanup. This is a bounded regression corpus, not exhaustive
fuzzing or a latency guarantee for arbitrary inputs.

The shared fixtures also cover string selection and NFC comparison, fallback
variants, declaration chains, bidi isolation, markup and parts, malformed
patterns, invalid models, and error recovery. Non-finite numeric text (`NaN`,
`Infinity`, and `-Infinity`) is asserted as `bad-operand` instead of being
passed to locale plural rules.

## What ICU Comparisons Actually Compare

`reference/icu4j` and `reference/icu4cxx` compile fixture source with ICU's
MessageFormat 2 APIs, format arguments in a locale, and compare the final string
with `expected` or a narrow `referenceExpected` override. They do not invoke a
Mojito runtime, compare Mojito's parsed model, inspect formatted parts, expose
selection operands/categories, or compare Mojito diagnostics.

The invalid-key recovery gate is intentionally split. ICU asserts the
observable selected output from
`reference/fixtures/selection-recovery/common`; the shared fallback case
asserts the required error. The generated plural-category path is different:
ICU4J supplies category results and every Mojito runtime reaches them through
its own public parser and portable formatter.

## Original Grouped-Million Gap

Before revision `50cecdfd93`, a locale-aware number declaration followed by
plural selection chose the fallback for `1000000` in French, Spanish, Italian,
and Portuguese. Babel rendered grouped text such as French `1 000 000`, and
the runtime reparsed that display string as the CLDR plural operand.

The ICU comparisons did not catch this for a verified structural reason:

1. The ICU harnesses ran ICU's formatter, not Mojito or its
   Babel/Intl/JDK/ICU4J/Foundation/ICU4X adapters.
2. Shared all-locale fixtures already contained large values including French
   `1000000`; this was not a missing locale/value row.
3. Shared conformance used the portable unlocalized registry, whose display
   stayed parseable and masked the adapter boundary defect.
4. Adapter tests compared direct display output but did not cross the
   formatting-to-selection or reannotation boundary.

The repaired contract keeps the source operand, semantic selection operand,
and localized display string distinct.

## Difference Disposition Ledger

### Confirmed Mojito Implementation Defects

| Root cause | One representative invariant | Pre-fix affected runtimes | Authority and disposition |
| --- | --- | --- | --- |
| Localized display reused as a plural operand | French `1000000` must select `many` after `:number` formatting | Locale-aware adapter families audited: Babel, Intl, PHP Intl, JDK/ICU4J, Foundation, ICU4X | ICU/CLDR agree; fixed by preserving semantic operands separately from display |
| Variant scan omitted numeric `BetterThan` | `integer-plural-before-fixed.json`: exact `1` must outrank earlier category `one` | All eight | ICU4J 78.3, ICU4C 77.1, vendored official integer behavior, and current TR35 Number Selection agree; existing fixture corrected, not duplicated |
| Exact matching used source/display spelling instead of canonical integer spelling | `number-exact-integer-serialization.json`: numeric `1` matches key `1`, not earlier `1.0` | All eight | ICU4J/ICU4C and TR35 Exact Literal Match Serialization agree. The fixture is inside TR35's required subset: an integer value with none of `minimumFractionDigits`, `minimumIntegerDigits`, `minimumSignificantDigits`, or `maximumSignificantDigits` set |
| Invalid numeric keys were silently treated as non-matches or stopped selection | `bad-number-variant-key-continuation.json`: report `bad-variant-key` and continue to exact `1` | All eight | TR35 requires the error; both ICU references continue to the valid exact output |
| A failed annotated input leaked its raw value | `failed-input-declaration-fallback.json`: invalid `:number` input formats as `{$foo}` | Python, Swift | Exact upstream basis: `third_party/message-format-wg/test/tests/functions/number.json` contains `.input {$foo :number} {{bar {$foo}}}` with string `foo`, expected `bar {$foo}`, and `bad-operand`; the ICU string-only harness cannot assert the required error. Other six runtimes already passed |
| Reannotation lost, retained, or filtered the wrong numeric options | `number-option-inheritance.json`: numeric `1` retains `minimumFractionDigits=1` and `signDisplay=always` through `:number` (`other +1.0`); `:integer` discards inherited minimum-fraction/minimum-significant options before a later `:number` (`1`); `:percent` retains `minimumFractionDigits=2` through a later `:number` (`1.00`) | All eight | Current TR35 defines function-specific option carry/override/discard rules. One shared output covers selection and all three display chains; ICU preview gaps are recorded below |
| Offset reannotation dropped inherited numeric display options | `numeric-chain-selection-and-options.json`: `1 :percent signDisplay=always` followed by `:offset add=1` formats `+2` | JavaScript, Python | The offset changes the semantic operand but does not erase unrelated inherited display options; one combined chain fixture exercises this together with selection provenance |
| Generated plural selection bypassed an earlier integer transformation | `numeric-chain-selection-and-options.json`: `1.9 :integer` followed by `:number` selects `one`, not `other` | Rust generated-category path | The vendored official integer cases establish truncation before later selection; fixed by replaying the semantic numeric chain instead of selecting from the original source value |
| Chained numeric functions consumed rounded display instead of the semantic resolved value | `number-reannotation-source-provenance.json`: Serbian `1.29` with max 1, then max 2, selects `other` and displays `1.29`; an `:integer` 1.25 copied through `:number` remains `1`; offsetting rounded-source `1.9` by +1 yields `2.9`; direct `-1.9 + 1` selects exact `-0.9`; `0.0 + 1` selects category `one` and formats canonically as `1` | All eight | TR35 requires final options to act on the semantic value, defines integer transformation and offset arithmetic, and the vendored official integer case establishes the transformed-integer boundary. The direct non-integer exact key is an explicit Mojito policy because TR35 leaves that serialization implementation-defined; ICU4J falls back for it |
| Currency option lookup crossed an intervening `:number` type barrier | `currency-through-number-override.json`: after USD `:currency` then `:number`, explicit `:currency currency=EUR` must format `Value €42.00`; without the explicit replacement Mojito reports `bad-operand` | Python Babel, PHP Intl | Fixed to match Mojito's established cross-runtime currency-type policy and to preserve the semantic number. ICU4J agrees on explicit EUR replacement but intentionally differs on the implicit-currency case, which is recorded once below; ICU4C does not support the probe |
| Portable/default formatting ignored `maximumFractionDigits` | `number-maximum-fraction-digits.json`: `1.29` with max 1 displays `1.3` | All eight portable/default shared-core formatters; Babel, JavaScript/PHP Intl, Foundation, and JVM ICU platform adapters already honored it | ICU4J and ICU4C agree; only a non-tie value is shared |
| JavaScript used prototype-bearing maps for valid names | `prototype-named-options-attributes.json` preserves `__proto__` in expression and markup options/attributes | JavaScript | Confirmed parser/model loss and prototype-setter risk; shared because the interchange model allows the name, without claiming ICU output comparability |
| JavaScript parsed a date-only value as a UTC instant | `2006-01-02 :date` in `America/Los_Angeles` must remain January 2, not shift to January 1 | JavaScript Intl | ICU4J/ICU4C agree; zone-less date/datetime values now resolve as wall-clock fields in the target zone, including DST gaps/overlaps, while explicit offsets remain instants |

Run the public shared regressions with:

```sh
sh conformance/check_all_languages.sh
```

### Adversarial QA Guardrails

| Confirmed gap | Durable regression and disposition |
| --- | --- |
| Empty or misrouted custom conformance suites could pass | The wrapper forwards one canonical fixture root to all eight runtimes, every runner rejects zero aggregate source models or format cases, and the full gate exercises empty/no-format suites plus direct Go/PHP path handling |
| JavaScript silently rounded integral operands outside its safe numeric range | Portable and Intl package tests cover number, integer, percent, exact selection, and offset. Unsafe values now produce bounded structured MF2 diagnostics instead of changed digits or a wrong branch; selection preserves its one additional `bad-selector`, and exact string/BigInt offset display remains supported |
| JavaScript fraction-digit options controlled unbounded work/output | Portable and Intl paths validate a maximum of 100 fraction digits before numeric conversion, host formatter construction, or output padding; bounded and hundreds-of-digits rejection cases are table-driven |
| Native numeric options leaked host arithmetic failures | Rust validates portable fraction-digit options against a 1,000-digit implementation limit before formatting or selection, so `maximumFractionDigits=65536` recovers with `bad-option`. Swift applies `:offset subtract` directly with checked decimal subtraction, so `subtract=-9223372036854775808` preserves representable results without overflowing an intermediate negation; decimal arithmetic failures and integral values that cannot round-trip through the portable `Double`/`Int64` boundary recover with `bad-operand` |
| Swift portable numeric conversion and padding trusted host bounds | Every portable `Double`-to-integer conversion used by formatting, selection-key generation, and selector matching is checked; percent scaling must remain finite; and minimum/maximum fraction digits are capped at 1,000 before `String(format:)` or padding. Direct, reannotated, and permissive-custom-formatter selector probes recover with bounded `bad-operand`, `bad-option`, and `bad-selector` diagnostics instead of trapping or allocating attacker-selected output |
| Recursive JavaScript source traversal leaked a host stack error on a 7,000-declaration chain | All inherited-source walks used by the public formatter are iterative; the package test runs the deep chain through portable and Intl registries and separately verifies that a host `RangeError` is returned as an `MF2Error` |
| Generated CLDR drift was not part of the maintained full gate | `check.sh` generates into a temporary root and compares exact working-tree and Git-index path/content sets without rewriting either copy |

### September safety, API and formatting regressions

- Every scanner terminates on a missing variant key; quoted braces and escaped
  pipes retain their literal meaning. A frontend wrapper regression exercises
  the original 42-character input below the editor's size guards.
- Swift JSON, relative-time conversion and week multiplication return typed
  failures instead of process traps. Rust/Swift portable decimal arithmetic
  preserves ordinary percentages, large supported integers and plural operands.
- Precision and decimal expansion are rejected before excessive padding or
  arithmetic. Go pads with one bounded repeat. Host limits remain explicit;
  see `../spec/runtime-limits.md`.
- Imported model validation covers required field/element shapes. Python/JS/Go/
  Kotlin returned parts cannot mutate the original catalog. Canonical-equivalent
  binding names resolve consistently; duplicate normalized declarations fail.
- JVM time styles, Babel date timezone rollover, fixed-unit relative time,
  partial short/narrow CLDR patterns and invalid currency codes are covered.
  JS Intl rejects impossible calendar dates before host normalization.
- `u:dir` accepts literals and variables, including `inherit`; invalid options
  emit diagnostics and are ignored. Plain aliases retain direction/isolation;
  a new annotation defaults to inherited direction without forced isolation.
  Private metadata carries production numeric direction and never leaks into
  the public parts schema or custom handler's resolved option lookup.
- Literal-only declaration histories memoize semantic values/options per format
  call. Long chain checks cover stack use, copying and arithmetic replay.
  Variable-dependent histories retain existing dynamic resolver behavior and
  are not claimed to have linear cost.
- JS Intl implements the showcase's advanced number display options. Selection
  with significant digits, nonstandard notation or legacy `:number style=percent`
  is explicitly rejected when the core cannot represent its semantics, rather
  than silently selecting an incorrect branch.

### Intentional or Runtime-Policy Differences

| Difference | Representative observation | Disposition |
| --- | --- | --- |
| Portable output versus localized display | The portable Russian number case emits `1.5`; ICU emits localized `1,5` | Compare selection/semantic outcomes at the portable layer and host output at adapter layers; do not change portable snapshots to ICU display |
| NFC names versus text | Binding/argument names normalize to NFC during evaluation; original parsed models and literal output keep their code points | Canonical equivalents are one binding; duplicate normalized declarations fail. Literal text normalization is a separate output contract. |
| Boolean and null host values | ICU4J, ICU4C, JSON, and host runtimes do not expose identical bool/null operand types or coercions | No shared expectation is inferred from a harness conversion or broad output mismatch alone |
| Default/tie rounding | Host libraries can differ on binary `1.005` and unspecified rounding defaults | The shared max-fraction regression uses non-tie `1.29`; host-specific tie behavior stays in adapter tests |
| Exact serialization outside TR35's canonical-integer subset | TR35 requires canonical integer spelling only when the resolved value is an integer and none of `minimumFractionDigits`, `minimumIntegerDigits`, `minimumSignificantDigits`, or `maximumSignificantDigits` is set; other cases are implementation-defined | The option-free integer case is normative. Direct offset result `-0.9` matching key `-0.9` is one deliberate Mojito cross-runtime policy (ICU4J 78.3 falls back); no broader decimal/option permutation matrix is claimed |
| Numeric precision and offset bounds | Shared regressions use ordinary finite values such as `1.29`, `1.9`, `-1.9`, and `0.0` | Runtimes use host numeric representations and practical limits. The suite does not claim arbitrary precision or identical overflow behavior; JavaScript explicitly rejects unsafe integral operands instead of coercing them, and TR35 permits implementation limits for out-of-range values |
| Currency type through `:number` | `.local $usd={42 :currency currency=USD}; .local $plain={$usd :number}` followed by `{$plain :currency}` | Mojito intentionally treats `:number` as a currency-type/provenance barrier, so an implicit final currency is a `bad-operand`; the barrier must preserve the semantic number, and an explicit final `currency=EUR` must format `Value €42.00`. ICU4J 78.3 instead carries USD and renders `Value $42.00`, while ICU4C 77.1 reports `U_MF_UNKNOWN_FUNCTION_ERROR`. One common reference row records the policy difference and one adapter row gates the objective explicit-replacement invariant |

### ICU Preview or Unsupported Surface

| Difference | Representative observation | Disposition |
| --- | --- | --- |
| Preview syntax/function gaps | ICU preview implementations reject some variable-offset declaration forms; ICU4C 77.1 reports `U_MF_UNKNOWN_FUNCTION_ERROR` for the targeted `:offset` and `:percent` rows | Keep those rows in the ICU4J-specific reference set plus adapter coverage; do not weaken shared behavior |
| Reannotated-local selection, integer filtering, and offset propagation in ICU4J | ICU4J 78.3 renders the combined option row as `fallback +1.0; integer 1.00; percent 1.00`: it preserves number/percent options, selects the wildcard, and fails to discard number fraction/significant options through `:integer`. It also falls back for exact decimal offset `-0.9`; a separate number → offset → number probe renders `1.0` instead of TR35/Mojito `2.0` | Narrow `referenceExpected.icu4j` values record output-observable differences; the extra propagation observation is documented once without another fixture |
| ICU4C function and resolved-value propagation gaps | ICU4C 77.1 reports `U_MF_UNKNOWN_FUNCTION_ERROR` for the strengthened common option row because it contains `:percent`. Earlier isolated probes select the expected category but drop inherited minimum-fraction display, and revive `1.25` after `:integer` then `:number` instead of preserving integer `1` | The common-source summary explicitly expects 36 pass plus 1 unsupported; earlier observations remain documented without weakening shared fixtures |
| Draft datetime surface | Date/time/datetime functions and semantic-skeleton options remain draft and differ across ICU and host libraries | Validate supported adapter contracts directly; do not claim cross-library string parity |
| Error visibility | ICU string APIs select the valid branch after an invalid numeric key but the harness cannot inspect `bad-variant-key` | Keep ICU output evidence separate from Mojito's exact-error fallback fixture |

### Locale-Data or Version Drift

The broad ICU4C 77.1 comparison differed from the generated/runtime CLDR data
for representative `cv`, `ie`, `kok`, and `sgs` plural rows. These are recorded
as version drift, not pinned as Mojito behavior. Generated all-locale fixtures
remain tied to the checked-in CLDR generation inputs and ICU4J category audit.

### Comparison-Layer Mismatches

- Broad ICU comparisons assert only final strings. They cannot establish model,
  parts, fallback-error, or intermediate-operand parity.
- Russian localization and the original grouped-million defect demonstrate why
  portable formatting, platform display, and selection semantics must be
  compared separately.
- Currency/date/time/timezone checks use each adapter's host library; locale
  data and default styles make cross-host string snapshots unsuitable.

## Dated Broad Diagnostic Baseline

The broad run is diagnostic, not a clean gate. On 2026-08-27 at base revision
`50cecdfd93`, before the new regressions increased the corpus beyond 845 output
cases:

| Reference | Total | Passed | Mismatched | Unsupported |
| --- | ---: | ---: | ---: | ---: |
| ICU4J 78.3 | 845 | 835 | 5 | 5 |
| ICU4C 77.1 | 845 | 797 | 22 | 26 |

Representative commands:

```sh
(cd reference/icu4j && sh run.sh compare ../../conformance/fixtures/source-to-model)
(cd reference/icu4cxx && sh run.sh compare ../../conformance/fixtures/source-to-model)
sh reference/check.sh
```

The first two commands intentionally surface documented preview, display, and
locale-version differences. `reference/check.sh` is a count-asserting ICU4J
gate and runs the ICU4C extension when available; set
`MF2_REQUIRE_ICU4C=1` to require that optional toolchain.

## Remaining Coverage Boundaries

- Parts intentionally use the documented narrower API, with exact upstream
  differences maintained as non-passes.
- Go has no platform formatting adapter. Rust ICU4X has no currency or
  relative-time adapter and uses portable percent behavior.
- ICU differentials expose strings, not parsed models, formatted parts,
  intermediate operands or Mojito diagnostics.
- Numeric host representations and limits differ. Supported bounded profiles
  are documented; arbitrary precision and universal host output parity are not
  claimed. Variable-dependent declaration histories remain a complexity limit.
- Source/model/output/diagnostic budgets beyond current numeric and application
  guards still need a shared configurable API before accepting arbitrary-size
  untrusted catalogs. Broad production load, concurrency and retention budgets
  are separate from the maintained development smoke tests.
- Configured MF2 asset integrity now runs on authoritative server save/import
  paths with locale context; activation and asset classification remain explicit
  repository policy. See the runtime review remediation note and tracker MF2-03.

Run all maintained runtime, data and reference layers from the repository root:

```sh
sh mf2/check.sh
# During development, validate generated working-tree files without staging:
sh mf2/check.sh --worktree
python3 mf2/packaging/smoke.py --runtime all --output /tmp/mf2-packages
```

CI uses strict generated/index freshness. Package smokes build real artifacts
and clean consumers; no command publishes or deploys them.
