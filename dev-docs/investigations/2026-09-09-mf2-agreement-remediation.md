# MF2 and grammatical agreement remediation

September 9, 2026 implementation and validation snapshot, recorded before commit.
The work was local, with no publication, deployment, or live repository
configuration change.

The reproduced priority failures and the additional confirmed runtime, agreement,
test-harness and packaging defects are repaired. The complete MF2 gate passes on
the final sources. Remaining feature and rollout boundaries are listed below.

## Scope and checkout boundaries

MF2 changes are in `/Users/ja/code/mojito` on `master`, starting at
`466ceb92bbad0bebed02649e56bd045a83ca4b3d`. Existing unrelated frontend work was
preserved. The agreement prototype is absent from this checkout: its fixes are
in `/Users/ja/.codex/worktrees/bd75/mojito`, branch
`ja/codex/compare-inflection-and-mf2`, starting at
`31391b83bdc2c43033c2931f529d2958f6d43f08`. Both checkouts were uncommitted at
the time of this validation snapshot. The agreement changes remain separate
from the MF2 and Mojito integration changes in the main checkout.

The original review and reproduction evidence are at
`/Users/ja/.cache/mojito-mf2-review-20260908/REVIEW.md`. Implementation logs,
fresh package artifacts and detailed lane reports are at
`/Users/ja/.cache/mojito-mf2-fixes-20260908/`. The final validation summary below
supersedes counts from intermediate runs in those directories.

## Correctness and resource fixes

| Finding | Implemented behavior |
| --- | --- |
| Parser nontermination | All eight parsers reject an empty invalid variant key instead of looping. The 42-character reproduction and malformed empty-brace variant have shared regressions; the frontend parser wrapper is covered too. |
| Swift process traps | JSON decoding, Foundation relative-time integer conversion and week multiplication use checked conversions/arithmetic. Unsupported values return decoding or MF2 errors. |
| Numeric corruption | Rust/Swift portable percentage and integer operations use bounded exact decimal arithmetic. Ordinary `0.29` percentages and Russian selection agree with decimal semantics. Go/PHP truncate supported integer text exactly; JVM casts reject out-of-range values. JavaScript fixes ordinary percent scaling and rejects nonzero decimal underflow instead of silently returning zero. |
| Allocation and history replay | Go pads with one bounded repeat. Precision/exponent limits reject excessive expansion before work. Literal-only numeric histories are memoized within a format call; recursive JVM traversal, Rust/Swift history handling and Swift parser copies were repaired. Custom callbacks cannot mutate cached semantic annotations. |
| Platform formatting | JVM time styles now select all four styles; Babel applies date timezones, preserves relative-time units and magnitude, handles partial locale patterns and minimum-only precision; JS rejects impossible ISO calendar dates and implements the demonstrated Intl number options. Unsupported advanced number selection reports diagnostics. |
| Models and results | Imported known model fields are validated. Swift native construction/encoding is public. Canonically equivalent names resolve consistently. Python, Go and Kotlin parts are detached; Python/JS callback annotations are detached and read-only. |
| Direction | All runtimes resolve literal/variable `u:dir`, inheritance and invalid-value recovery. Numeric direction comes from private registry metadata and pinned locale data. The handler option lookup excludes `u:dir`, and public parts retain their existing contract. |
| Diagnostics | Missing operands, unknown functions, failed selectors, canonical duplicate declarations and fallback text have explicit regression expectations, including exact error multiplicity. |

The concrete numeric profiles and callback ownership rules are documented in
[`mf2/spec/runtime-limits.md`](../../mf2/spec/runtime-limits.md). The current
direction behavior follows the message-context and isolation rules in the
[Unicode MF2 specification](https://www.unicode.org/reports/tr35/tr35-messageFormat.html).

## Conformance, build and distribution gates

- Every runtime now has a direct official-suite bridge using its production
  registry. Only upstream's intentionally custom test functions are supplied
  by test hooks. Every specified output, parts result and complete diagnostic
  list is checked.
- The upstream corpus is pinned to
  `5c4ddb27e726fd7881c1787a632efba83ab0d850`: 19 vendored files, 16 JSON suites,
  462 tests and 761 assertions. A full-file SHA-256 inventory and denominator
  checks reject deletions, additions and modified expectations. Custom corpora
  require an explicit unverified developer mode.
- Expected differences identify an exact assertion, test-content hash, actual
  result and reason. Unexpected failures and stale dispositions fail the gate.
  Parts API differences and unsupported functions remain visible non-passes.
- The shared corpus has 80 source models, 877 format cases, 12 parts cases,
  seven fallback cases, one fallback-parts case, 28 invalid sources, 33 format
  errors and ten locale keys. A separate deterministic 413-input parser mutation
  batch runs under a 20-second process deadline in all eight runtimes.
- Seven platform registries execute the shared 47-case adapter corpus. Go has
  no separate platform registry. Rust's unsupported currency case remains an
  explicit non-pass.
- A new eight-runtime CI matrix runs native, shared, official and adapter gates
  plus artifact consumers. A shared job checks pinned CLDR generation and fixture
  integrity. This workflow has been checked locally; no remote CI run is claimed.
- Clean consumers install actual wheel, npm, JVM JAR, Cargo, SwiftPM source,
  Go module and Composer artifacts. Python also builds and validates its source
  distribution. Archive checks verify exact license/notice bytes. The package
  script records source/artifact hashes and tool versions, excluding generated
  test caches from source staging and hashing.
- Harness negative tests cover missing assertions, invalid transport, subprocess
  crashes/timeouts and changed corpus inventories. PHP syntax errors now fail
  the shell gate. Performance driver tests verify fixture forwarding and build
  setup before sampling. Actual benchmark-driver regressions enforce locale/bidi
  options, UTF-8 checksums and expected-output checks for every case before timing,
  including cases outside the requested timed iteration count. Parser drivers
  preflight valid and invalid corpora and reject invalid iteration counts.

CLDR regeneration uses immutable revision
`1aaabe99aa652d6f22ea488cf25baea46aa69b42`, including direction-data input hashes.
`check_generated.sh --worktree` validates uncommitted generated contents against
regeneration. The default gate additionally checks the Git index for committed CI.

## Agreement prototype

All eight reproduced agreement findings were repaired in the separate checkout:

- Supported simple/quoted messages compile once; unsupported declarations,
  selection and functions fail at binding instead of returning partly evaluated
  source. Returned term forms and caller values are appended once as opaque text.
- Java/Python share Unicode scanning, UTF-16 span and numeric lexical contracts.
  Manifest/pack locales must be compatible; invalid Unicode encoding is rejected.
- Turkish explicit number and French NFC/Unicode-whitespace handling agree
  between implementations.
- The corpus includes 59 pinned upstream examples: 51 supported results and eight
  unavailable German form rows, distinguished explicitly. Seventeen release
  fixtures round-trip with metadata; concurrent reuse has a 1,000-call regression.

The agreement implementation remains a Java term renderer with Python reference
and generation tools. It is not agreement support in all eight MF2 runtimes or
full upstream Inflection conformance.

## Mojito validation integration

The opt-in repository/extension `MF2` integrity checker uses the existing ICU
candidate evaluator. Server save, review, guarded correction and import paths
provide the authoritative target locale. REST preflight accepts locale identity;
legacy no-locale callers get structural validation without assuming English.
The CLI can preflight the configured MF2 contract before activation.

Existing PM/admin and import bypass policies are preserved. An invalid imported
target remains available for repair as `TRANSLATION_NEEDED` and is excluded from
localized output. This work wires source behavior; it does not activate a live
checker or migrate mixed-format classification.

## Validation

Final verification:

- `sh mf2/check.sh --worktree`: passes on the final sources, including static
  checks, all eight runtime selectors, shared data, demos and ICU references.
  Local command/toolchain details and full output are in `full-mf2-check.log`.
- Official gates: 11,415 assertions across 15 profiles; 10,974 pass and 441 are
  exact maintained capability/API differences. There are zero unexpected
  failures or stale dispositions. Each portable profile has 717 passes and 44
  differences; six platform profiles have 750/11 and Rust has 738/23.
- All eight artifact builds and clean consumers pass. Independent verification
  confirms current source hashes and all nine produced archive hashes. Python's
  two archives are the source distribution and installed wheel.
- Fifteen official-harness and 12 packaging/benchmark-driver tests pass.
- Whitespace checks pass for authored changes. The two trailing spaces at
  `mf2/third_party/message-format-wg/test/tests/syntax.json:132` and `:303` are
  preserved upstream bytes, verified by the pinned corpus inventory.
- Frontend: 331 tests across 17 relevant files; TypeScript build and targeted
  lint pass. The callback dependency correction has an additional 28-test rerun.
- Babel: 64,920 production-registry calls across all 1,082 installed Babel 2.18
  locales, with no exceptions or diagnostics for the supported cases.
- CLDR: 210,824/210,824 plural comparisons; number and relative-time validation.
- Backend checker/mutation/import/preflight lanes: 104 tests, zero failures/errors/skips;
  real English/Arabic configured-checker regressions cover save and localized
  asset import. The updated database synchronization spy's DB suite was not run.
- Agreement: 747 Java tests, 284 Python release-gate tests, six shared Python
  contract tests and the fixture smoke pipeline pass. Three opt-in performance
  and retained-heap smoke tests also pass.
- Final format/valid-parse/invalid-parse profiles all pass. At 1,000 measured
  iterations and 100 warmups, all eight runtimes produce the same 6,636 UTF-8
  format bytes; valid parsing produces 1,000 models and zero diagnostics;
  invalid parsing produces zero models and 1,000 diagnostics. Valid/invalid
  source-byte totals also agree at 96,873/38,930. ICU reference subsets are
  reported separately. See the [profile report](/Users/ja/.cache/mojito-mf2-fixes-20260908/perf/PROFILE_FINAL.md).

Detailed verification: [full gate log](/Users/ja/.cache/mojito-mf2-fixes-20260908/full-mf2-check.log),
[package artifacts and source hashes](/Users/ja/.cache/mojito-mf2-fixes-20260908/packaging-status.md),
[backend test totals](/Users/ja/.cache/mojito-mf2-fixes-20260908/backend/final-summary.json),
[agreement results](/Users/ja/.cache/mojito-mf2-fixes-20260908/grammar-status.md).

## Remaining adoption boundaries

These fixes do not claim full upstream conformance or general production readiness.
The maintained parts API retains model metadata instead of exposing all upstream
resolved parts. Go platform formatting, Rust currency/relative time, wider
agreement lexicons and full message evaluation in the prototype remain explicit
capability limits. A shared configurable source/model/output/diagnostic budget API
and classification rollout remain tracked work. Typed Rust/Swift model round trips
still discard unknown extension fields; semantic model validation does not imply
lossless editing of arbitrary extension metadata.

Variable-dependent option histories preserve resolver semantics and are not
covered by the linear-history performance claim. Go's per-call source cache adds
private state to `FunctionSource`: use keyed literals or the constructor; external
unkeyed struct literals need migration. Custom Go callback histories are copied
defensively and incur proportional copy cost. Package READMEs describe other
runtime-specific limits.

Benchmarks distinguish build time, process RSS, cumulative allocation and warmed
execution. Local smoke/scaling samples demonstrate repaired behavior; they do not
establish production latency percentiles, throughput budgets or absence of leaks.

## Commit readiness review

A subsequent review of the combined main-checkout changes passed the full MF2
gate again, including the Git-index CLDR check, and verified that the eight
package source trees and nine archives still match their passing consumer
reports. The combined application passed 1,206 frontend tests and 140
backend/CLI/evaluator/database tests, formatting, lint, TypeScript and the
production build. The five database regression tests used disposable in-memory
HSQL with local application configuration disabled.

This review corrected the locale assertion shared by two frontend tests,
avoided placeholder-candidate parsing when assisted editing is inactive, and
updated the stale backend design description. Browser checks confirmed placeholder
insertion and undo/redo. npm proxy, Maven/framework, Vite bundle/import and React
test `act(...)` warnings remain; none failed these checks. These local checks do
not establish deployment or live checker activation.

## License packaging follow-up

A focused license audit found that the packages included the license files but
used a CLDR copyright notice from an older release, and the Python distribution
metadata omitted the bundled Unicode data license. The canonical Unicode license
and all ten package copies now match `cldr-core/LICENSE` at the pinned CLDR JSON
revision, including its 2004–2026 copyright notice. Their `NOTICE` files link to
that exact source. Python now declares `Apache-2.0 AND Unicode-3.0`, and the
artifact gate verifies the expression inside both wheel and source-distribution
metadata.

All 14 packaging tests, eight fresh runtime builds and consumers, and both
ICU4J adapter builds passed. All eleven archives contain the corrected notices.
Kotlin compiler warnings and Python's deliberately disabled byte-compilation
warnings remain. These builds are local; nothing was published or deployed.
