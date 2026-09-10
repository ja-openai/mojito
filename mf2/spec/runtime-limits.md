# MF2 runtime limits and ownership

The runtimes reject unsupported numeric work before large padding or exponent
expansion. These are Mojito implementation limits, not Unicode requirements.
They do not constitute a configurable source/model/output budget API.

## Numeric profiles

| Runtime | Fraction-digit option limit | Operand/arithmetic boundary |
| --- | ---: | --- |
| JavaScript | 100 | Finite host numbers; integral number/percent/selection operands must be safe integers. Exact integer offset text uses bounded BigInt arithmetic and at most 4,096 places of expansion. |
| Python | 1,000 | Decimal coefficient length and absolute adjusted exponent are bounded at 1,000; numeric text is bounded before Decimal conversion. Arithmetic results are checked again. |
| Rust / Swift portable | 1,000 | Exact decimal coefficient arithmetic with at most 4,096 expanded digits; large exponent, padding and arithmetic results are checked before allocation. |
| Java / Kotlin | 1,000 | BigDecimal precision and expanded representation are bounded at 4,096 digits before formatting/offset arithmetic. Portable, JDK and ICU4J integer conversion requires a finite numeric value in `-2^63 <= value < 2^63`; out-of-range values fail instead of clamping. |
| Go / PHP | 1,000 | Finite host numeric formatting; decimal offset and exact integer truncation expansion are bounded at 4,096 places. Integer text does not narrow to a signed-64-bit value. Go uses one bounded repeat for padding. These are not arbitrary-precision general number formatters. |

A minimum above the maximum is `bad-option`. Invalid/excessive operands are
`bad-operand`; failed selection also reports the relevant selector diagnostic.
A host-backed registry can have stricter representability limits than portable
arithmetic. Cross-runtime applications should stay inside the intersection of
these profiles; a 1,000-digit option is not supported in JavaScript.

The JVM integer limit applies after the existing binary numeric conversion, so
decimal values close to the endpoint may round outside the supported range.
Integer selection reports `bad-selector` when its converted operand is outside
that range. Plain numeric argument conversion in Java, Kotlin and Go preserves
the host number's decimal representation without a narrowing integer cast.

Swift JSON decoding accepts representable native integers and bounded finite
numbers without unchecked conversions. Very large integral JSON numbers that
cannot be represented without losing their integer value return `DecodingError`.
Use the explicit numeric-string value API for larger supported exact operands.
Foundation numeric adapters enforce their documented native precision bound;
relative-time conversion and week multiplication use checked arithmetic.

## Histories, caches and callback ownership

Semantic operands remain separate from rendered localized strings. Production
formatting memoizes literal-only numeric/option histories within one format
call. Rust shares immutable history tails and preserves its public Send/Sync
traits. Swift retains only the variables referenced by a source's options and
reuses its parser character storage. JVM source traversal and native history
release avoid recursive stack growth on the maintained long-chain regressions.

Variable-dependent option histories keep existing resolver behavior and are
not covered by a linear-cost claim. Custom callback histories can have additional
copying or disable memoization; consult each package's contract. There is no
global cache retaining arbitrary message models or argument values.

Python and JavaScript callbacks receive detached read-only function annotations
and source metadata. Read function options through the callback API; edit the
caller's catalog between format calls to change a message. Returned parts are
separate result values and cannot mutate the original catalog. Unknown extension
metadata is preserved as opaque data and does not acquire formatting semantics.

`u:dir` is resolved by the message context and omitted from the handler's resolved
option lookup. It accepts literal/variable `ltr`, `rtl`, `auto` and `inherit`.
Invalid values emit `bad-option` and are ignored. A new annotation defaults to
inherited direction without forced isolation; an unannotated alias reuses its
operand's direction and isolation. Numeric direction is private metadata derived
from the production registry and pinned locale data, not from scanning output.
The default API isolation setting remains `none`; choose `default` to apply the
Unicode-style isolation algorithm.

## Remaining application budgets

Applications must still bound catalog/source size, declaration count, argument
size, variant combinations, output and diagnostics appropriate to their workload.
The browser's existing 65,536 UTF-16-unit/1,024-brace guards and the backend
integrity evaluator's limits are application-specific policies. They are not
universal library limits or a substitute for terminating parser loops.

The maintained gate exercises 413 short parser mutations under a 20-second
whole-batch deadline, ordinary and extreme numeric boundaries, long declarations,
and package consumers. Those checks demonstrate the repaired failures; they do
not establish production latency percentiles, arbitrary-size safety, constant
memory or a full leak/concurrency certification.
