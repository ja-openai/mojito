# MF2 Platform Adapter Parity

This note tracks concrete differences between the current MF2 platform-backed
function registries. It is intentionally scoped to runtime behavior and package
boundaries; portable/unlocalized registries should stay dependency-free.

Last audited: 2026-06-27.

The top-level `mf2/check.sh` gate now runs explicit smoke checks for the
implemented JavaScript Intl, Swift Foundation, PHP Intl, Java/Kotlin ICU4J, and
Rust ICU4X adapters, plus Python Babel when Babel is installed. That gate proves
the adapters keep loading and formatting representative messages, but it is not
yet a shared output-parity fixture because the demos still use different locale
tags and case matrices.

## Registry Coverage

| Runtime | Registry | Platform library | number | integer | percent | currency | date/time/datetime | relativeTime |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Java | `Mf2FunctionRegistry.defaults()` | JDK | yes | yes | yes | yes | yes | no |
| Java | `Mf2Icu4jFunctions.registry()` | ICU4J | yes | yes | yes | yes | yes | yes |
| Kotlin | `Mf2FunctionRegistry.defaults()` | JDK | yes | yes | yes | yes | yes | no |
| Kotlin | `Mf2Icu4jFunctions.registry()` | ICU4J | yes | yes | yes | yes | yes | yes |
| JavaScript | `createIntlFunctionRegistry()` | `Intl` | yes | yes | yes | yes | yes | yes |
| Python | `babel_function_registry()` | Babel | yes | yes | yes | yes | yes | yes |
| Swift | `MF2FunctionRegistry.foundation` | Foundation | yes | yes | yes | yes | yes | Apple platforms only |
| Rust | `FunctionRegistry::icu4x()` | ICU4X | yes | yes | portable fallback | no | yes | no |
| PHP | `IntlFunctions::registry()` | PHP Intl | yes | yes | yes | yes | yes | no |
| Go | none yet | deferred | no | no | no | no | no | no |

Notes:

- Rust uses ICU4X, not ICU4J. Its adapter currently covers ICU4X-backed
  decimal and date/time formatting only. `:percent` remains the portable
  unlocalized formatter because the current ICU4X crates in use do not expose a
  production percent/unit-pattern path here.
- PHP Intl exposes `NumberFormatter` and `IntlDateFormatter` in the current
  environment, but not `IntlRelativeTimeFormatter`, so `:relativeTime` stays out
  of the adapter.
- Go remains deferred. `golang.org/x/text/message` is useful for localized
  numeric printing, but it does not provide the complete date, time, currency,
  and relative-time surface needed for an honest MF2 platform registry.

## Behavioral Differences

### Number Grouping

Java and Kotlin JDK defaults intentionally disable grouping for `:number`,
`:integer`, and `:percent`; currency still uses the JDK currency formatter with
grouping. The richer platform adapters generally keep platform grouping:

- Java/Kotlin JDK: `en-US number=12345.678`
- Java/Kotlin ICU4J: `en-US number=12,345.678`
- JavaScript Intl / Python Babel: `en number=12,345.68` in the current demos
- Swift Foundation: `fr number=12 345,678`
- PHP Intl: `en-US number=12,345.678`
- Rust ICU4X: `fr number=12 345,50`

Decision needed: if `defaults()` is meant to be platform-pretty on the JVM, JDK
number/integer/percent should probably stop disabling grouping. If the no-group
choice is deliberate for product strings, document that JVM defaults are
"localized symbols without grouping" rather than fully platform-pretty.

### Locale Tags And Digit Shaping

The current demos do not all use the same locale tags:

- Java, Kotlin, and PHP use `en-US`, `fr-FR`, `ja-JP`, and `ar-EG`.
- JavaScript and Python demos use `en`, `fr`, `ja`, and `ar`.
- Swift and Rust demos cover one or a few locales per function rather than the
  full four-locale matrix.

This matters most for Arabic. `ar-EG` through ICU4J/PHP Intl shapes digits to
Arabic-Indic digits, while the current JavaScript/Python `ar` demos produce
Latin digits for some numeric output. A dedicated parity demo should use the
same locale tags and input values across every adapter before treating output
differences as library differences.

### Date And Time Option Aliases

Adapters now converge on these public options:

- `dateStyle` for `:date` and the date half of `:datetime`.
- `timeStyle` for `:time` and the time half of `:datetime`.
- `timeZone` for date/time formatting where the host library can honor it. Rust
  ICU4X currently accepts only `UTC`, `Etc/UTC`, or no value and reports a
  diagnostic for other zones.

Legacy aliases are retained for compatibility:

- `length` remains an alias for `dateStyle` on `:date`.
- `precision` remains an alias for `timeStyle` on `:time`.
- `dateLength` and `timePrecision` remain aliases for the two halves of
  `:datetime`.
- shared `style` remains a fallback when no explicit date/time style is present.

Python Babel is the one remaining semantic caveat: Babel exposes one datetime
style rather than independent date and time styles. The adapter accepts
`dateStyle` and `timeStyle`, but currently requires them to match when both are
provided.

### Date/Time Time Zone Semantics

The safest common baseline is UTC:

- Java/Kotlin JDK, Java/Kotlin ICU4J, JavaScript Intl, Python Babel, Swift
  Foundation, and PHP Intl accept `timeZone`; the demos use UTC for stable
  cross-platform output.
- Rust ICU4X requires `timeZone=UTC` and returns a bad-option diagnostic for
  other time zones.

The common contract is: support `timeZone` when the backend can do it, or reject
unsupported zones explicitly. Do not silently ignore a non-UTC `timeZone`.

### Relative Time

Relative time is the least portable platform function:

- ICU4J Java/Kotlin supports `numeric=always|auto`, `style=long|short|narrow`,
  and units from second through year. `numeric=auto` can produce words such as
  `tomorrow`.
- JavaScript Intl supports relative time through `Intl.RelativeTimeFormat`.
- Python Babel supports relative time through Babel.
- Swift Foundation supports relative time on Apple platforms through
  `RelativeDateTimeFormatter`; non-Apple Swift remains deferred until validated.
- Java/Kotlin JDK defaults, Rust ICU4X, PHP Intl, and Go do not support
  `:relativeTime`.

Recommended follow-up: keep relative time out of defaults unless a real host
library provides it. Do not add generated CLDR relative-time data to tiny core
packages without an explicit adapter/resource strategy.

### Currency

The platform adapters consistently require a three-letter currency option and
do not register currency selectors. Rust ICU4X and Go do not currently expose a
platform currency adapter. Currency formatting still varies by host library and
locale data version: JDK `ja-JP` currently rounds EUR to zero fraction digits in
the demo, while ICU4J/PHP Intl keep cents for the same value.

### Percent Scaling

All current platform adapters treat `0.1234` as `12.3%` when a percent function
is registered. This matches portable behavior and should remain part of the
cross-language contract.

## Recommended Follow-Up Slices

1. Replace the per-adapter smoke demos with a shared platform parity fixture or
   add a shared fixture underneath them. Use the same source strings, values,
   locale tags, and option names across all adapters; keep assertions
   structural where host locale data legitimately differs.
2. Decide whether Java/Kotlin JDK defaults should enable grouping for
   `:number`, `:integer`, and `:percent`.
3. Add a datetime-style mapping note for Python Babel or a richer Babel
   implementation if we decide to support mixed `dateStyle`/`timeStyle` values.
4. Keep Go deferred until a real adapter shape is clear. A partial numeric-only
   registry would look more complete than it is and would not solve the platform
   parity problem.
