# Unicode Micro-Runtime Review Findings

This note tracks high-signal findings reported by the review thread
`019ea4ad-6c67-7862-ae34-bbb93b936192` while the Unicode/CLDR micro-runtime
branch is under active implementation.

## Fixed

- JavaScript package-boundary coverage: the package boundary runtime and type
  tests now import the direct number-core, date-time-core, and
  relative-time-core `ToParts` helpers from their public subpaths, and continue
  asserting those implementation helpers stay off the root package export. This
  catches drift where a parts implementation exists but the published subpath
  surface or declarations stop exposing it.
- JavaScript number-core registry parity: `createNumberCoreFunctionRegistry`
  now returns a portable registry with generated `number`, `integer`, `percent`,
  and `currency` formatters layered on top. This matches the JavaScript
  date-time/relative-time registry helpers and the JVM number-core helpers
  instead of returning a constructor-built registry that omits portable
  fallbacks.
- Python package-boundary coverage: the package boundary runtime and mypy tests
  now import the explicit number-core, date-time-core, and relative-time-core
  parts helpers from their public submodules while asserting those experimental
  helpers remain off the root package export. The Python README now documents
  the explicit core `*_to_parts` wrappers and type-boundary coverage.
- Direct-core ASCII digit grammar: Python, Java, Kotlin, and Swift direct
  number-core and relative-time operands, date-time fixed-offset time zone
  options, date string operands, and formatter exact numeric source parsing now
  use explicit ASCII `[0-9]` grammar instead of Unicode digit predicates or host
  decimal/date constructors that accept Arabic-Indic lookalikes. Shared
  fixtures reject non-ASCII digits in number-core, relative-time, date-time
  date operands, date-time `timeZone`, and exact numeric selector paths so one
  runtime cannot silently normalize them to ASCII decimal values while others
  reject them.
- Semantic date-time skeleton numeric grammar: `fractionalSecond` widths now
  require exactly one ASCII digit from `1` through `9` across JavaScript,
  Python, PHP, Go, Java, Kotlin, Rust, and Swift. Shared date-time fixtures
  reject Arabic-Indic digit lookalikes and numeric-coercion spellings such as
  `2.0`, preventing host integer/number parsers from accepting widths that
  other runtimes reject.
- Numeric option ASCII grammar: portable `:number`/`:percent` fraction digit
  options and number-core registry fraction digit options now reject non-ASCII
  digit lookalikes instead of accepting host Unicode digit predicates in
  Python and Kotlin. Shared source-to-model and number-core registry fixtures
  cover Arabic-Indic `minimumFractionDigits`.
- PHP/Rust direct-parts coverage: existing conformance tests now assert that
  direct number-core and date-time-core parts helpers return the same single
  text part shape as their direct string helpers, matching the relative-time
  parts coverage that was already present. The PHP and Rust READMEs now call
  out the direct core parts helpers alongside the string helpers.
- Go relative-time formatter coverage: the direct relative-time core tests now
  compare `NewRelativeTimeCoreFormatter(...).Format` and `FormatToParts`
  against the top-level helpers so the prepared formatter API stays covered
  alongside the static direct helpers and registry path.
- Shared conformance runner Swift execution: the all-language runner now invokes
  SwiftPM with `swift run --disable-sandbox` for the Swift slices and points
  SwiftPM cache/config/security state at package-local `.build` paths. This
  avoids SwiftPM manifest sandbox failures and user-cache write warnings in the
  managed Codex workspace while still running the same Swift conformance
  binaries and core checks.
- Shared conformance runner package-boundary coverage: the all-language runner
  now includes the JavaScript public subpath runtime/type checks, packed CLDR
  resource checks, portable formatter regression checks, and the Python
  unittest/mypy package-boundary checks. The standard cross-runtime command now
  catches direct core parts export drift and JavaScript package check drift
  instead of leaving those checks as separate per-runtime commands.
- Shared conformance runner JVM API coverage: the all-language runner now
  includes Java's public API recovery/parts smoke check and the quiet default
  JDK registry checks for both Java and Kotlin. That keeps the JVM public
  formatter surfaces and real localized registry wiring in the standard
  cross-runtime validation path.
- Shared conformance runner Rust coverage: the all-language runner now executes
  Rust's full default-free test suite after the CLI conformance run instead of
  filtering the integration tests down to the shared core fixtures. This keeps
  Rust public API recovery, invalid-source, format-error, locale-key, and direct
  core checks in the standard validation path.
- Top-level MF2 check consolidation: `mf2/check.sh` now delegates to
  `conformance/check_all_languages.sh` for shared cross-runtime conformance
  coverage instead of duplicating stale per-runtime commands. The remaining
  Swift demo invocation, Swift static build, and Swift performance scripts use
  the same package-local SwiftPM cache/config wrapper as the all-language
  runner, so these entry points are executable in the managed Codex workspace.
- Go gate cache isolation: `mf2/check.sh`, `mf2/static_check.sh`, and the
  all-language conformance runner now share a small Go cache environment helper
  that creates invocation-scoped `/private/tmp` cache paths unless callers
  explicitly provide `GOPATH`, `GOMODCACHE`, or `GOCACHE`. This prevents a
  stale shared module cache from breaking maintainer validation before tests
  run while preserving caller overrides.
- Official Unicode runner documentation: the conformance README now describes
  the Rust, Java, JavaScript, Go, and PHP official-test scoreboard runners and
  their shared baseline instead of implying that only the Rust runner is wired.
- Shared conformance runner coverage: `mf2/conformance/check_all_languages.sh`
  now runs the shared number-core, date-time-core, and relative-time-core
  fixture suites for Rust, Swift, Python, Java, Kotlin, and JavaScript instead
  of only exercising those core fixtures through ad hoc per-runtime commands.
  Go and PHP already run their full fixture-backed test suites from the
  umbrella script. The Java and Kotlin slices prepare their Maven classpaths
  once and then reuse them for all core checks.
- Numeric exact selector precedence: exact numeric variant keys now outrank
  plural-category matches for `:number`, `:integer`, `:percent`, and `:offset`
  selectors across JavaScript, Python, Java, Kotlin, Go, Rust, Swift, and PHP.
  Shared fixtures cover `:integer`, `:number`, and `:percent` cases where a
  category variant appears before the exact numeric variant.
- Parser strictness: unknown escapes inside `|...|` quoted literals now produce
  parse diagnostics instead of preserving the backslash as literal content.
  Shared invalid-source fixtures cover placeholder literals, function option
  values, and variant keys across all runtime parsers.
- Parser strictness: MF2 syntax whitespace now uses the narrow ASCII syntax
  space set plus U+3000 IDEOGRAPHIC SPACE and the existing bidi-marker padding
  exception instead of broad host Unicode whitespace APIs. Shared invalid-source
  fixtures cover NBSP, NNBSP, and EM SPACE between a placeholder operand and
  function annotation, while the official unquoted-literal cases for non-syntax
  Unicode spaces still pass.
- Parser official-reference escapes: text escapes now unescape `\|` in the
  normal message text path across JavaScript, Python, PHP, Go, Java, Kotlin,
  Rust, and Swift. Shared source fixtures cover the official `\\\{\|\}`
  spelling and U+3000 placeholder padding so source-to-model conformance checks
  catch the behavior even if an official runner treats syntax files as
  parse-only.
- Parser strictness: malformed variable-valued function and markup option
  values now emit parse diagnostics instead of entering the model as unresolved
  variables. Shared invalid-source fixtures cover empty, leading-digit, and
  trailing-junk `$` option values such as `minimumFractionDigits=$bad!`.
- Numeric exact selector precision: exact `:number`, `:percent`, and `:integer`
  selector comparisons now preserve source decimal identity instead of comparing
  binary floating-point values in JavaScript, Java, Kotlin, Go, Rust, Swift, and
  PHP. Python already used `Decimal`. Exact `:number` selection also gives a
  higher rank to the preferred source key when multiple numeric-equivalent keys
  are present: plain decimal operands keep their original spelling while
  exponent-form operands prefer the canonical decimal key, matching ICU4J for
  cases such as `1`/`1.0` and `1e2`/`100`. Exact `:percent` selection applies
  the same tie-break after scaling the source operand by 100, so `0.010`
  prefers key `1.0` and `0.01` prefers key `1` independent of variant order.
  Shared fixtures cover fractional and large integer decimal strings that
  previously collided after float coercion, plus numeric-equivalent
  source-spelling tie-breaks for number and percent selectors.
- Failed input propagation: Python and Swift now treat failed `.input` names as
  unavailable everywhere the other runtimes already did, instead of falling back
  to the original argument value for later selectors, expressions, or
  variable-valued options. Shared fallback fixtures cover a failed selector
  operand and a failed input reused as `minimumFractionDigits=$d`.
- Failed input propagation follow-up: Go portable number/offset helpers and the
  number-core function registry now propagate variable-valued option lookup
  failures instead of silently falling back to default option values. The shared
  failed-input option-variable fixture covers the portable `:number` path.
- Portable `:offset` exact integer arithmetic and bounds: JavaScript, Python,
  PHP, Go, Java, Kotlin, Rust, and Swift now parse offset operands/options with
  exact integer semantics below the documented `abs(value) < 1e21` bound, check
  arithmetic results before formatting, and recover as MF2 errors instead of
  losing JavaScript precision, coercing PHP output through floats, wrapping
  fixed-width integers, surfacing raw Python `int()` errors, or trapping Swift
  arithmetic. Shared portable fixtures cover large exact formatting, large
  exact selection, operand bounds, option bounds, and result bounds.
- Portable `:offset` raw-value strictness follow-up: JavaScript and PHP now
  validate offset arithmetic from raw host operands instead of rendered strings,
  so unsafe JavaScript `number` values and unsafe PHP `float` values cannot
  become rounded exact integers before offset arithmetic. Python and Swift now
  use explicit ASCII digit grammar for portable decimal/integer parsing, and
  PHP plural preselection for huge exact offset selector values now fails
  closed without emitting host warnings before exact-key ranking. Shared
  fixtures cover non-ASCII digit operands and variant keys, with targeted
  JavaScript and PHP regression tests covering host numeric exactness and PHP
  warning suppression.
- Python numeric bounds: portable `:number`, `:percent`, and `:integer` now
  reject huge exponent operands and oversized rendered decimal output as MF2
  `bad-operand` failures instead of materializing output proportional to the
  exponent or surfacing raw `decimal.Overflow`/host integer conversion errors.
  Shared fallback fixtures cover huge-exponent number, percent, and integer
  formatting, and generated Python plural operands reject out-of-bounds decimal
  exponents.
- Portable numeric exact formatting: dependency-free `:number`, `:percent`, and
  `:integer` formatters now render source decimal operands from exact decimal
  components across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift
  instead of passing large source literals through host float/integer types.
  The formatter path shares the portable output-size bound with Python, so
  oversized exponent output still fails as `bad-operand`. A shared fixture
  covers large `:number`, `:integer`, `:percent`, and `minimumFractionDigits`
  formatting; JavaScript and PHP also have targeted regressions for the
  reviewer repro and PHP warning suppression.
- Portable numeric raw operand bounds: dependency-free `:number`, `:percent`,
  and `:integer` operand parsing now rejects decimal/integer operand text longer
  than 256 characters before regex matching, float parsing, host
  `BigDecimal`/`Decimal` construction, or internal decimal normalization across
  JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift. Shared fallback
  fixtures cover oversized decimal operands for all three portable formatters.
  Decimal operand exponent parsing is also capped at `1_000_000` before host
  decimal construction across those runtimes; Java and Kotlin now share the same
  bounded-exponent path as the decimal-operand runtimes that already used a
  compact internal representation.
- Numeric fraction digit bounds: portable numeric formatters and number-core
  direct APIs now cap `minimumFractionDigits`, `maximumFractionDigits`, and
  equivalent platform wrapper fraction options at the supported formatter
  boundary instead of allowing raw host exceptions or output proportional to an
  attacker-controlled option value. Portable `:number` now also honors
  `maximumFractionDigits` rounding, and portable `:number`/`:percent` reject
  inconsistent `minimumFractionDigits > maximumFractionDigits` option pairs as
  `bad-option`, and portable max-fraction rounding now uses ICU/Intl-style
  half-expand instead of half-even. Shared format/fallback fixtures cover
  `minimumFractionDigits=10000` on `:number` and `maximumFractionDigits=10000`
  on `:number`/`:percent`, inconsistent `:percent` fraction bounds, and
  half-expansion for `:number`/`:percent` tie values.
- Number-core implicit fraction defaults: direct number-core APIs and explicit
  number-core registries now match ICU/Intl sibling-option default adjustment
  across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift. When only
  `minimumFractionDigits` exceeds the style default maximum, the implicit
  maximum is raised; when only `maximumFractionDigits` is below the
  style/currency default minimum, the implicit minimum is lowered. Explicit
  inconsistent pairs still recover as `bad-option`. Shared fixtures cover
  number, percent, and currency minimum-only cases, USD currency maximum-only
  `0`/`1`, and registry `:currency maximumFractionDigits=0`.
- Number-core magnitude bounds: direct number-core APIs and the explicit
  number-core registries now reject scaled absolute values at or above `1e21`
  as MF2 `bad-operand` across JavaScript, Python, PHP, Go, Java, Kotlin, Rust,
  and Swift. This prevents JavaScript exponent strings from being grouped as
  decimal digits, Python `Decimal` quantization from surfacing raw exceptions,
  PHP integer/percent overflow behavior, and oversized decimal materialization
  in decimal-backed ports. Shared number-core fixtures cover number, integer,
  and percent-scaled magnitudes; JavaScript also rejects unsafe `BigInt`
  operands before `Number()` coercion.
- Relative-time quantity bounds: relative-time-core now rejects resolved display
  quantities above `1_000_000_000` as MF2 `bad-operand` across JavaScript,
  Python, Go, Java, Kotlin, PHP, Rust, and Swift, preventing PHP warnings,
  fixed-width integer clamps, and implementation-defined casts for huge finite
  operands such as `1e30`. The shared relative-time fixture covers the reviewer
  repro and direct API tests cover the same bound.
- JVM direct numeric operand exceptions: Java and Kotlin number-core and
  relative-time-core direct APIs now catch hostile `toString()` failures from
  non-primitive operands and recover as MF2 `bad-operand` instead of leaking raw
  unchecked host exceptions. Targeted Java and Kotlin core tests cover the
  throwing-operand path.
- JVM direct date/time operand exceptions: Java and Kotlin date-time-core direct
  APIs now also catch hostile `CharSequence.toString()` failures and recover as
  MF2 `bad-operand` instead of leaking raw unchecked host exceptions. Targeted
  Java and Kotlin date-time core tests cover the throwing-operand path.
- Relative-time data validation: data-explicit relative-time constructors and
  registry helpers now reject empty `localeMap`, empty `patternSets`, and
  pattern-set entries with empty ids or empty data maps as MF2
  `missing-locale-data` across JavaScript, Python, PHP, Go, Java, Kotlin, Rust,
  and Swift instead of accepting unusable generated-data shells and failing
  later in locale lookup. Swift also normalizes duplicate pattern-set ids with
  the same last-entry-wins map preparation used by the other runtimes instead
  of trapping in `Dictionary(uniqueKeysWithValues:)`.
- Relative-time locale-map shape: JavaScript, Python, and PHP now reject
  non-string generated-data `localeMap` values as `missing-locale-data` during
  data preparation, matching typed runtime expectations and avoiding raw Python
  `TypeError` paths for unhashable set ids.
- Relative-time empty-locale fallback: JavaScript direct relative-time core now
  normalizes an explicit empty locale to the default `en`, matching Python,
  PHP, Go, Java, Kotlin, Rust, and Swift. Direct relative-time tests in each
  runtime cover the fallback without weakening top-level message-format locale
  validation.
- Relative-time operand grammar: relative-time-core string operands now use the
  same ASCII decimal grammar across JavaScript, Python, Go, Java, Kotlin, PHP,
  Rust, and Swift before host numeric parsing, rejecting host extensions such as
  `0x10` and `0x1p4` as MF2 `bad-operand`. Shared fixtures cover the JS
  base-prefix repro, the JVM/Go hex-float risk, and invalid leading-plus,
  leading-dot, trailing-dot, leading-zero, and fractionless-exponent strings
  while preserving negative decimal and exponent notation.
- Relative-time signed zero direction: JavaScript, Python, PHP, Go, Java,
  Kotlin, Rust, and Swift now treat `-0` as past for `numeric=always`, matching
  `Intl.RelativeTimeFormat` output such as `0 seconds ago`, while preserving
  existing zero-relative terms such as `now` for `numeric=auto`. The shared
  relative-time fixture covers string `"-0"` through the registry, and the
  direct/reference checks include native signed-zero witnesses where practical.
- Relative-time extended relative terms: `numeric=auto` now probes the generated
  CLDR relative-term map with the full signed resolved quantity instead of only
  `-1`/`0`/`1`, so locale terms such as French `après-demain` and
  `avant-hier` are used when present. Shared fixtures cover French `±2` day
  registry formatting, and direct/reference checks compare the `+2 day` case
  against `Intl.RelativeTimeFormat`.
- Relative-time exact relative terms: `numeric=auto` now uses CLDR relative
  terms such as `tomorrow` and `yesterday` only when the selected unit boundary
  is exact. Fractional explicit units such as `0.6 day` still render with the
  rounded numeric quantity, but no longer round into natural calendar terms.
  Shared fixtures cover future and past fractional explicit-day registry
  formatting across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift.
- Number-core operand grammar: direct number-core string operands now use the
  same ASCII decimal grammar across JavaScript, Python, Go, Java, Kotlin, PHP,
  Rust, and Swift before host numeric parsing. Shared fixtures cover exponent,
  leading-plus, leading-dot, trailing-dot, leading-zero, and fractionless
  exponent decimal forms while rejecting host extensions such as `0x10` and
  `0x1p4` as MF2 `bad-operand`. The JavaScript official Unicode runner now
  passes its checked-in `461/0` baseline instead of skipping the eight
  number-literal grammar cases.
- Date/time timestamp bounds: date-time-core direct numeric timestamp parsing now
  uses the portable `0001-01-01T00:00:00Z` through
  `9999-12-31T23:59:59.999Z` millisecond range across JavaScript, Python, PHP,
  Go, Java, and Kotlin. Values outside that range reject as MF2 `bad-operand`,
  including the former ECMAScript-only `+/-8640000000000000` boundary accepted
  by JS/PHP/Go but not Python. The shared date-time fixture covers huge and
  just-outside-portable rejection; numeric timestamp fixtures cover the portable
  inclusive endpoints and truncation of fractional negative milliseconds toward
  zero in the runtimes with numeric date-time operands.
  Go now accepts the normal primitive numeric family for direct timestamp
  operands instead of only `int`, `int64`, and `float64`, while still rejecting
  unsigned values above the portable maximum. Rust and Swift normalize numeric
  fixture values through their string-based date-time APIs and reject
  out-of-range numbers via the ISO parser.
- Date/time skeleton field-width bounds: direct date-time-core skeleton parsing
  now rejects skeleton field runs wider than 32 as MF2 `bad-option` across
  JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift, preventing
  attacker-sized numeric padding from inputs such as a 10,000-character `d`
  field. The shared date-time fixture covers the reviewer repro class with an
  oversized day field.
- Date/time fixed-offset `V` skeleton widths: direct date-time-core formatting
  now follows ICU's width-specific fixed-offset labels across JavaScript,
  Python, PHP, Go, Java, Kotlin, Rust, and Swift: nonzero offsets use `unk` for
  `V`, a padded `GMT+HH:mm` zone ID for `VV`, and `Unknown Location` for `VVV`,
  while zero offsets compact every `V` width to the locale CLDR GMT-zero label.
  `VVVV` keeps the existing localized GMT fallback. Shared fixtures cover the
  reviewer `+05:30` repro values and the zero fixed-offset `+00:00` boundary.
- Date/time operand strictness and object boundaries: date-time-core now rejects
  impossible calendar dates, invalid fixed-offset operand values, and arbitrary
  host object coercion as MF2 `bad-operand` instead of relying on host parser
  normalization or surfacing raw coercion exceptions. JavaScript uses a strict
  ISO string parser, Python validates ISO offsets before `fromisoformat`, PHP
  validates offsets before `createFromFormat` and avoids implicit object
  stringification, Go rejects `fmt.Stringer` host objects instead of accepting
  their rendered ISO text, Swift disables lenient fixed format parsing and
  prevalidates ISO offsets, Rust rejects time-only string operands instead of
  mapping them onto the Unix epoch, and Java/Kotlin reject arbitrary object
  operands instead of formatting `toString()` results while accepting direct
  `LocalDate`, `LocalTime`, and `LocalDateTime` host values with the same UTC
  interpretation as accepted string forms. Shared fixtures cover the invalid
  date/offset cases, time-only string rejection, valid fixed-offset source
  normalization, and source offsets that normalize outside the portable date
  range; direct language tests cover object boundary recovery and JVM local
  host date/time values.
- Python variable-valued option coercion: registry formatting now converts
  throwing host objects used as option variables into recoverable MF2
  `bad-option` errors instead of letting raw Python exceptions escape.
  Host-object fallback rendering also avoids raw `__str__` exceptions.
- JavaScript formatter error normalization: registry formatting now converts
  throwing host objects used as option variables into recoverable MF2
  `bad-option` errors, and wraps any remaining native formatter exception as
  an MF2 `error` diagnostic instead of returning raw `Error` objects without a
  stable `code`.
- Host-object value rendering: JavaScript and Python now treat throwing host
  objects used as ordinary placeholder, `:string`, or selector input values as
  recoverable MF2 `bad-operand` errors instead of letting raw JavaScript errors
  escape or silently rendering an empty Python string with `ok=True`.
- Go date-time/relative-time option-variable propagation: Go registry
  adapters now propagate failed variable-valued option lookups for date-time
  `style`/style aliases/`timeZone`/`calendar`/`skeleton`/`hourCycle` and
  relative-time `style`/`numeric`/`policy`/`unit`, producing recoverable MF2
  `missing-argument` fallback instead of silently formatting with defaults.
  The same slice also tightened Go ISO operand offsets to reject values outside
  the fixed-offset boundary such as `+18:01`.
- Swift numeric bounds: portable `:number`, `:percent`, and `:integer`,
  plural operand conversion, JSON numeric argument rendering, number-core
  integer-style formatting, and relative-time quantity formatting now avoid
  unchecked `Double` to `Int`/`Int64` conversions for finite values outside the
  host integer range. Swift-specific runner guards cover the reviewer repros
  `{1e20 :number}`, `{1e18 :percent}`, `{1e20 :integer}`, large number-core
  integer truncation, and large relative-time quantities.
- Annotation-only fallback source: unsupported function annotations without an
  operand now recover as `{:<name>}` instead of including option source text.
  This matches the official `{:f k=v}` fallback expectation and avoids
  surfacing literal option values from unsupported no-operand functions. Full
  `expressionSource` metadata still preserves options for non-fallback parts.
- Primitive numeric locale rendering: unannotated numeric host values now
  format through number-core with the message locale when rendered as ordinary
  expressions, while function annotations, selector operands, and string-valued
  numeric-looking arguments keep their raw operand text. Shared fixtures cover
  the official `fr` decimal-comma repro for JavaScript, Python, PHP, Go, Java,
  Kotlin, Rust, and Swift.
- Local alias raw-value identity: unannotated `.local` declarations now keep the
  resolved raw operand instead of storing the display string. This fixes the
  reviewer repro where a JavaScript `Date` or Python `datetime` alias failed
  when later passed to `:datetime`, and it closes the numeric alias variant
  where locale-formatted display text was reparsed as a decimal. A shared
  portable `:number` fixture covers the numeric alias path across JavaScript,
  Python, PHP, Go, Java, Kotlin, Rust, and Swift, with targeted JS/Python
  date-time host-value checks for the direct repro.
- Date/time inherited-source locals: date-time-core registry wrappers now use
  inherited function source values before reparsing localized local display
  strings, and registry style parsing normalizes `timePrecision=second` through
  the same medium-style compatibility path as JavaScript/Rust. A shared
  `date-time-core` registry fixture covers the reviewer
  `.local $d = {|2006-01-02| :datetime ...} {{{$d :date}}}` repro across
  JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift.
- Date/time style `second` validation regression: the `timePrecision=second`
  compatibility alias is now applied only when the selected option is the
  legacy `timePrecision`/`precision` path. Real `style`, `dateStyle`, and
  `timeStyle` values of `second` recover as `bad-option` across JavaScript,
  Python, PHP, Go, Java, Kotlin, Rust, and Swift. Shared direct and registry
  date-time-core error fixtures cover the reviewer repros.
- Number-core inherited operand stability: number-core registry calls now use
  inherited source operands instead of reparsing localized local display
  strings across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift.
  The integer-to-number chain truncates the inherited integer source before
  formatting so `.local $n = {1.25 :integer} ... {$n :number}` stays `1`
  while avoiding locale-specific digit parsing failures. Shared number-core
  registry fixtures cover Arabic `:integer` to `:number` and French `:number`
  to `:percent` chains.
- Number-core inherited currency options: explicit `:currency` registry calls
  now resolve `currency` from the current call or inherited source chain across
  JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift. This preserves
  variable-valued currency codes when a local currency expression is reformatted
  later, while still treating present empty/invalid current options as
  validation failures. A shared number-core fixture covers
  `.local $price = {$amount :currency currency=$currency} {{{$price :currency}}}`.
- Host-adapter inherited numeric/currency sources: the JavaScript Intl,
  Python Babel, and PHP Intl registries now recover numeric operands from
  inherited numeric/currency sources before reparsing localized local display
  strings, and JavaScript/Python now inherit variable-valued currency options
  while still rejecting present invalid current options. Adapter tests cover
  `.local $price = {$amount :currency currency=$currency} {{{$price :currency}}}`
  and the invalid-current-option override case.
- Host-adapter inherited date/time sources: the JavaScript Intl, Python Babel,
  and PHP Intl registries now also walk inherited date/time source chains before
  parsing localized local display strings. This aligns them with the Java,
  Kotlin, and Swift host adapters for annotated locals such as
  `.local $date = {$instant :date dateStyle=full ...} {{{$date :date dateStyle=short ...}}}`;
  targeted adapter tests cover the French localized-display repro.
- JVM host-adapter currency option errors: Java and Kotlin JDK `:currency`
  formatters now classify missing or invalid `currency` options as `bad-option`
  instead of `bad-operand`, matching JavaScript Intl, Python Babel, PHP Intl,
  Swift Foundation, and number-core option semantics. JDK registry demos cover
  both invalid-current and missing-currency cases.
- JavaScript primitive BigInt rendering: unannotated `bigint` host values now
  route through number-core with the message locale just like finite numeric
  primitives, including the existing unsafe-BigInt `bad-operand` bound.
- Number-core affix sign placement: percent and currency formatting now applies
  CLDR pattern affixes before placing synthetic signs, so prefix-currency
  locales format negative and explicit-positive money values as `-$1,234.56`
  and `+$1,234.50` instead of `$-1,234.56` and `$+1,234.50`. The fix landed
  across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift, with
  shared number-core fixtures and Intl/JDK/Foundation reference coverage for
  negative `en-US` currency.
- Number-core currency code spacing: `currencyDisplay=code` now applies CLDR
  `currencySpacing` insertions when the currency sign is directly adjacent to a
  numeric placeholder, matching Intl for prefix-code locales such as `en-US`
  and `ja-JP` without double-spacing suffix-code patterns. Static generated
  data now carries compact before/after spacing strings for Go, Java, Kotlin,
  Rust, and Swift. Shared fixtures cover direct formatter output and the
  reviewer registry repro; JavaScript keeps direct Intl witnesses for
  `en-US`, `ja-JP`, `fr-FR`, and `ar-EG`.
- Number-core decimal half rounding: JavaScript, Go, and Rust no longer round
  supported fixed-fraction decimal output through binary floating fixed-point
  formatting. Their number-core rounders now derive the fixed-point value from
  canonical decimal text before grouping/localizing, matching ICU/Intl-style
  half-up output for cases such as `1.005 -> 1.01` and `0.015 USD -> $0.02`.
  Shared number-core fixtures cover string and numeric operands, and
  JavaScript keeps direct Intl witness checks for the reviewer repros.
- Go direct number-core exact decimal precision: direct `FormatNumberCore` now
  keeps accepted `int64`, unsigned integer, string decimal, and finite float
  operands in the same exact decimal representation used by the portable
  formatter instead of narrowing through `float64` before formatting. Targeted
  Go tests cover the reviewer repro values `9007199254740993` and
  `9223372036854775807` as both native `int64` and decimal strings with
  grouping disabled.
- Direct number-core decimal string precision: JavaScript, PHP, and Rust direct
  number-core APIs now parse accepted decimal string operands into bounded
  decimal operands before integer truncation, percent scaling, rounding,
  grouping, and sign handling. This closes the direct API precision drift where
  `9007199254740993` formatted as `9007199254740992` after narrowing through
  host binary floats. Shared number-core fixtures cover number and percent
  output with grouping disabled.
- JavaScript direct number-core exponent expansion: the JavaScript generated
  number-core rounder now rounds decimal operands by `digits`/`scale` before
  converting to fixed-point text, matching the PHP/Go/Rust bounded-decimal
  shape and avoiding large zero expansion for compact inputs such as
  `1e-100000` with small `maximumFractionDigits`. A direct unit regression
  guards against reintroducing large internal string repeats.
- Number-core signed zero and explicit zero signs: JavaScript, Python, PHP, Go,
  Java, Kotlin, Rust, and Swift now preserve `-0` through number, percent, and
  currency formatting and show `+0` for `signDisplay=always`. Shared fixtures
  cover direct string `-0` operands and MF2 literal registry integration, while
  JavaScript keeps direct Intl signed-zero witnesses.
- Number-core `useGrouping` option validation: number-core registries now
  accept only `true` or `false` for string-valued `useGrouping` options across
  JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift. Invalid and empty
  literals recover as `bad-option` instead of silently enabling grouping. The
  JavaScript/Python/PHP direct string-capable helpers share the same strict
  boolean parser, and shared registry fixtures cover valid `false`, `bogus`,
  and empty literals.
- PHP host-object formatter recovery: ordinary placeholders, annotated
  expressions, and selectors now catch throwing `__toString()` host values
  inside the per-expression/per-selector recovery path. PHP returns the
  expression fallback with `bad-operand` instead of letting one hostile object
  escape to the top-level formatter catch and erase the whole message.
- PHP option-variable error classification: variable-valued option coercion now
  uses a dedicated option path, so throwing host option values recover with
  `bad-option` while ordinary expression and selector operands continue to
  recover with `bad-operand`. PHP now also normalizes host-thrown `MF2Error`
  exceptions from option-value `__toString()` methods to `bad-option`, so host
  error codes cannot escape through option coercion. PHP conformance covers
  number-core expression and selector option-variable recovery for native and
  MF2 host exceptions.
- Date/time style hour-cycle overrides: direct `timeStyle` formatting,
  date-time style joins, and exact semantic `timeStyle` aliases now honor
  explicit `hourCycle` options and locale `u-hc` extensions across JavaScript,
  Python, PHP, Go, Java, Kotlin, Rust, and Swift. Explicit same-family options
  such as `ja-JP` full `timeStyle` with `h23`/`h24` now rewrite only the style
  pattern's hour field, preserving locale literals such as `時`/`分`/`秒` and
  fixed-offset zone labels. Locale `u-hc` extensions and cross-family
  conversions still derive a compact CLDR time skeleton and use the
  hour-cycle-aware matcher, which keeps `ja-JP-u-hc-h23` aligned with Intl's
  colon-separated full-time style. Shared fixtures cover explicit `h23`,
  locale `u-hc-h23`/`u-hc-h24`, 24-to-12 conversion in `fr-FR`, long style zone
  formatting, `ja-JP` full-style same-family preservation, semantic style
  aliases, Intl reference cases, and registry `:time`.
- Semantic date+time style joins: generated date-time data now includes a
  compact `dateTimeStyleJoinFormats` overlay for lexical full/long style joins
  such as French `à`, German `um`, Arabic `في`, and English `at`. Direct
  `dateStyle`/`timeStyle` formatting and exact semantic style aliases use this
  overlay across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift,
  while ordinary skeleton composition continues to use CLDR `dateTimeFormats`.
  Shared fixtures cover direct French/German/Arabic joins plus semantic
  French/German JS Intl reference witnesses; JVM/Foundation harnesses mark the
  lexical join cases as known host-library differences where their style
  formatters still use generic joins.
- Date/time explicit empty option literals: date-time registry adapters now
  reject supplied empty string literals across JavaScript, Python, PHP, Go,
  Java, Kotlin, Rust, and Swift instead of letting empty `calendar`,
  `hourCycle`, `timeZone`, or `skeleton` options fall through as omitted
  defaults. Omitted options still default normally, and shared registry error
  fixtures cover empty `style`, `calendar`, `hourCycle`, `timeZone`, and
  `skeleton` literals.
- MF2 `u:dir` handling: expression direction metadata now participates in
  default bidi isolation across JavaScript, Python, PHP, Go, Java, Kotlin,
  Rust, and Swift, including direction inherited through local aliases.
  Invalid expression `u:dir` literals now recover as `bad-option` formatter
  errors instead of escaping through JavaScript, PHP, Go, Java, or Kotlin hard
  failures, and markup `u:dir` is rejected as `bad-option` in Python and Swift
  to match the existing ports. Shared fixtures cover an inherited `rtl`
  expression and combined invalid expression/markup recovery.
- JavaScript/Python/PHP direct API coercion hardening: direct `number-core` now
  wraps non-coercible operands, locales, and fraction digit options as typed
  core errors in these ports, direct `date-time-core` wraps non-coercible
  locales, skeletons, time zones, calendars, hour cycles, and style options as
  typed core errors where those options exist, and direct `relative-time-core`
  wraps non-coercible operands, locales, and option enums as typed core errors.
  Targeted JavaScript, Python, and PHP tests cover these host-object paths.
- Official Unicode parts assertions: the JavaScript official-test runner now
  validates the upstream `expParts` assertions in the wired official files by
  projecting Mojito's public parts into the official parts vocabulary for
  comparison. The checked-in baseline pins 20 official parts assertions across
  syntax, number, fallback, and `u:` option cases while preserving the
  Mojito-specific public parts shape.
- Date/time skeleton total bounds: direct date-time-core skeleton options now
  reject inputs longer than 256 code points across JavaScript, Python, PHP, Go,
  Java, Kotlin, Rust, and Swift before semantic parsing or best-pattern
  matching. The shared date-time fixture covers an alternating-field skeleton
  that bypasses the per-field width cap.
- Direct locale option bounds: number-core, date-time-core, and the
  data-explicit relative-time core now reject locale option strings longer than
  256 characters across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and
  Swift before locale-key normalization or Unicode extension parsing. Shared
  number-core/date-time-core fixtures cover the consumed conformance paths, and
  the proposal relative-time fixture records the same `bad-option` expectation.
- Locale-key direct lookup bounds: shared locale-key canonicalization and
  lookup-chain helpers now fail closed for source or feature-parent locale
  strings longer than 256 characters across JavaScript, Python, PHP, Go, Java,
  Kotlin, Rust, and Swift. Shared locale-key fixtures cover oversized
  canonical sources, structural lookup sources, and recursive feature-parent
  branches.
- Date/time direct option string bounds: direct date-time-core APIs now reject
  `timeZone`, `calendar`, and `hourCycle` option strings longer than 256
  characters across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift
  before fixed-offset parsing or enum validation. Shared date-time-core fixtures
  cover oversized values for each option.
- Date/time style option string bounds: string-based direct APIs and registry
  adapters now reject `style`, `dateStyle`, `timeStyle`, `length`, `precision`,
  `dateLength`, and `timePrecision` option strings longer than 256 characters
  before style enum validation. Java, Kotlin, and Swift direct APIs expose typed
  style enums, so the string guard is exercised through their registry adapters.
  Shared date-time-core fixtures cover common direct and registry alias paths.
- Relative-time option string bounds: relative-time-core now rejects `style`,
  `numeric`, `policy`, and `unit` option strings longer than 256 characters
  across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift before enum
  validation or generated-data lookup. The proposal relative-time fixture covers
  oversized values for each option.
- Number-core option string bounds: number-core direct APIs and explicit
  registries now reject free-form `currency` plus registry string options
  `currencyDisplay`, `signDisplay`, and `useGrouping` longer than 256
  characters across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift
  before enum/boolean/currency-code validation. Shared number-core fixtures
  cover oversized currency and registry option literals.
- Number-core raw operand text bounds: number-core direct APIs and inherited
  registry source operands now reject decimal operand strings longer than 256
  characters across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift
  before regex matching or host decimal construction. The shared number-core
  fixture covers an oversized fractional operand that otherwise stays within
  the numeric magnitude bound.
- Date/time raw operand text bounds: date-time-core direct APIs and inherited
  registry source operands now reject ISO operand strings longer than 256
  characters across JavaScript, Python, PHP, Go, Java, Kotlin, Rust, and Swift
  before regex matching or host date/time parser attempts. The shared
  date-time-core fixture covers an oversized fractional-second operand.
- Relative-time raw operand text bounds: relative-time-core direct APIs and the
  experimental registry source operands now reject numeric operand strings
  longer than 256 characters across JavaScript, Python, PHP, Go, Java, Kotlin,
  Rust, and Swift before regex matching or host float parsing. The proposal
  relative-time fixture covers an oversized decimal operand that otherwise stays
  within the quantity bound.
- Generated plural operand text bounds: generated CLDR plural-rule operand
  helpers now reject raw operand strings longer than 256 characters across
  JavaScript, Python, Go, Rust, Swift, Java, and Kotlin before regex matching,
  host float parsing, or decimal construction. PHP's manual plural operand
  wrapper applies the same bound before its regex and float parsing, and the
  throwing runtimes use fixed error messages for length failures rather than
  echoing the oversized operand. A shared source-to-model fallback fixture covers
  an oversized exponent operand that would otherwise parse as English `one`.
- Go/Kotlin invalid plural `other` matching: Go generated plural helpers and
  Kotlin's plural wrapper now return no category for invalid or unsafe operands
  instead of `other`, matching the other runtimes and preventing an invalid
  ordinal operand from selecting a literal `other` variant. A shared
  source-to-model fixture covers the unsafe-integer `select=ordinal` repro.
- Direct core parts API consistency: number-core and date-time-core now expose
  direct text-part wrappers in Java, Kotlin, Go, and Swift, matching the existing
  JavaScript, Python, PHP, and Rust public API surface. Focused runtime checks
  assert that each wrapper returns a single `text` part equal to the sibling
  formatted string.

## Open

- No high-signal correctness, conformance, security, OOM/DoS, or ICU/reference
  mismatch finding from the current review thread is open in this note. Keep
  monitoring thread `019ea4ad-6c67-7862-ae34-bbb93b936192` for fresh compact
  repros and add a new focused bucket if one is validated.
