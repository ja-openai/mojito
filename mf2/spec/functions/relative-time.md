# Relative Time Function

Status: experimental Mojito/UMF registry function draft. This document is not
part of the Unicode MessageFormat 2 core grammar or data model.

The `:relativeTime` function formats a duration relative to the present, such
as `30s ago`, `in 2 hr.`, or `yesterday`. It combines product policy for unit
selection with CLDR relative-time field data for localized rendering.

This draft defines a duration-seconds v0. Timestamp operands, time zones,
calendar-day boundaries, date fallback, and UI refresh scheduling are deferred.

## Conformance

The key words `MUST`, `MUST NOT`, `SHOULD`, `MAY`, and `OPTIONAL` are to be
interpreted as normative for implementations that claim conformance to this
draft.

A conforming implementation MUST implement the processing algorithm in this
document for numeric duration operands and MUST use CLDR-compatible
relative-time patterns for its supported locale data set.

## Function Identifier

The function identifier is `relativeTime`.

Example:

```mf2
{$deltaSeconds :relativeTime style=narrow policy=precise numeric=always}
```

`relativeTime` is a formatter function. It is not a selector in v0.

## Operand

The operand is a duration in seconds.

- Negative values refer to the past.
- Positive values refer to the future.
- Zero is the present.

An implementation MUST accept finite integer and decimal numeric operands. It
MAY accept numeric strings if they can be parsed without locale-specific
symbols. It MUST reject `NaN`, infinities, booleans, objects, arrays, and
non-numeric strings with `bad-operand`.

The input unit is always seconds in v0. Producers that already have
millisecond timestamps MUST convert them before calling the function or use a
future timestamp-aware function mode.

## Options

All option values are literal identifiers unless otherwise stated.

| Option | Values | Default | Description |
| --- | --- | --- | --- |
| `style` | `long`, `short`, `narrow` | `short` | Selects the CLDR relative-time style. |
| `numeric` | `always`, `auto` | `always` | Controls whether natural relative terms may be used. |
| `policy` | implementation policy name | `precise` | Selects the adaptive unit policy. |
| `unit` | `auto`, `second`, `minute`, `hour`, `day`, `week`, `month`, `quarter`, `year` | `auto` | Forces a unit or requests policy-based selection. |

Unknown option names SHOULD be rejected with `bad-option`. Unknown option
values MUST be rejected with `bad-option`.

The `policy` option names a policy supplied by the implementation. This draft
defines three built-in policies: `precise`, `compact`, and `chat`. Products MAY
register additional named policies. MF2 messages SHOULD reference named
policies rather than inline threshold values, so translators do not edit product
timing behavior.

## Units

The following fixed unit durations are used for duration-seconds v0:

| Unit | Seconds |
| --- | ---: |
| `second` | 1 |
| `minute` | 60 |
| `hour` | 3600 |
| `day` | 86400 |
| `week` | 604800 |
| `month` | 2592000 |
| `quarter` | 7776000 |
| `year` | 31536000 |

`month`, `quarter`, and `year` are fixed-duration approximations in v0. They
MUST NOT be interpreted as calendar-relative months, quarters, or years.

## Built-In Policies

Policies map an absolute duration in seconds to an output unit and a quantity.

All range lower bounds are inclusive. All upper bounds are exclusive. `inf`
means no upper bound.

### `precise`

| Absolute seconds | Unit |
| ---: | --- |
| `0 <= n < 60` | `second` |
| `60 <= n < 3600` | `minute` |
| `3600 <= n < 86400` | `hour` |
| `86400 <= n < 604800` | `day` |
| `604800 <= n < 2592000` | `week` |
| `2592000 <= n < 31536000` | `month` |
| `31536000 <= n < inf` | `year` |

### `compact`

| Absolute seconds | Unit |
| ---: | --- |
| `0 <= n < 60` | `second` |
| `60 <= n < 3600` | `minute` |
| `3600 <= n < 86400` | `hour` |
| `86400 <= n < inf` | `day` |

### `chat`

| Absolute seconds | Unit | Notes |
| ---: | --- | --- |
| `0 <= n < 45` | `second` | With `numeric=auto`, use the `second` relative `0` term when available. |
| `45 <= n < 2700` | `minute` | |
| `2700 <= n < 79200` | `hour` | |
| `79200 <= n < 604800` | `day` | |
| `604800 <= n < inf` | `week` | A UI MAY choose to stop auto-updating and render an absolute date outside this function. |

## Unit Selection

Given operand `x`, let `a = abs(x)`.

1. If `unit` is not `auto`, the selected unit is the explicit unit.
2. Otherwise, the selected unit is determined by the selected policy and `a`.
3. Let `unitSeconds` be the selected unit's fixed duration.
4. Let `quantity = roundHalfExpand(a / unitSeconds)`.
5. If `a > 0` and `quantity == 0`, set `quantity = 1`.
6. If `a == 0`, set `quantity = 0`.

`roundHalfExpand` rounds to the nearest integer, with `.5` values rounded away
from zero. Since `a` is non-negative, this is equivalent to `floor(value + 0.5)`.

The output direction is:

- `past` when `x < 0`
- `future` when `x > 0`
- `present` when `x == 0`

For `numeric=always`, `present` is formatted using the selected unit's
`future` numeric pattern with quantity `0`.

## Locale Data Lookup

Locale identifiers are canonical BCP47-style keys such as `en`, `fr-CA`, and
`zh-Hant-TW`. Implementations SHOULD accept underscores and ignore Unicode
extension sequences for lookup if their locale helper already does so.

Locale data lookup is:

1. Canonicalize the requested locale.
2. Walk the implementation locale fallback chain.
3. Select the first locale with relative-time data.
4. Select the requested style and unit.
5. Select `past` or `future` pattern data for numeric output.

If no supported locale data is found, the implementation MUST fail with
`missing-locale-data` unless it explicitly documents an English fallback mode.

## Plural Category Selection

Numeric relative-time patterns are keyed by CLDR plural category. To format a
numeric relative time:

1. Select the CLDR cardinal plural category for `quantity` in the requested
   locale.
2. Look up the category-specific pattern.
3. If the category is missing, use the `other` pattern.
4. If `other` is missing, fail with `missing-locale-data`.

The plural category is selected from the absolute output quantity, not from the
signed input seconds.

## Pattern Substitution

The numeric pattern is a CLDR relative-time pattern. If the pattern contains
the substring `{0}`, replace each occurrence with the formatted quantity.

If the pattern does not contain `{0}`, return the pattern unchanged. This is
required for locales whose singular or dual forms include the number in words,
such as Arabic patterns for `one` and `two`.

The v0 conformance fixtures use ASCII decimal integer quantity strings to
isolate relative-time behavior from locale-sensitive number formatting.
Production implementations MAY use locale-sensitive number formatting if that
behavior is documented and tested separately.

## `numeric=auto`

When `numeric=auto`, the implementation MAY use natural relative terms from
CLDR `relative-type-*` data.

Natural terms are used only when all of the following are true:

1. The selected quantity is `0` or `1`.
2. The selected unit has a relative term for the signed offset:
   - `0` when `x == 0`
   - `-1` when `x < 0` and `quantity == 1`
   - `1` when `x > 0` and `quantity == 1`
3. The selected policy did not suppress natural terms.

If those conditions are not met, formatting MUST use the numeric pattern path.

Examples:

- `x = 0`, `unit = day`, `numeric = auto` -> `today` in English
- `x = -86400`, `unit = auto`, `policy = precise`, `numeric = auto` -> `yesterday` in English
- `x = 86400`, `unit = day`, `numeric = always` -> `in 1 day` in English long style

For the `chat` policy, `numeric=auto` with `0 <= abs(x) < 45` SHOULD use the
`second` relative `0` term when available. In English this is `now`.

## Custom Policies

An implementation MAY register additional named policies. A custom policy MUST
be deterministic and SHOULD be described by the same range table shape as the
built-in policies:

```json
{
  "name": "product-chat",
  "ranges": [
    { "lessThanSeconds": 45, "unit": "second", "autoRelativeZero": true },
    { "lessThanSeconds": 2700, "unit": "minute" },
    { "lessThanSeconds": 79200, "unit": "hour" },
    { "lessThanSeconds": 604800, "unit": "day" },
    { "lessThanSeconds": null, "unit": "week" }
  ]
}
```

Custom thresholds are part of application code or generated configuration, not
translator-authored message text. If a message references an unknown policy, the
implementation MUST fail with `bad-option`.

## Fallback Formatting

Strict formatting MUST raise the errors defined above. Fallback formatting MAY
emit the source expression and collect the runtime error using the existing MF2
fallback result shape.

For example, if locale data is missing for `zz`, strict formatting raises
`missing-locale-data`; fallback formatting may produce `{$delta :relativeTime}`
with a collected `missing-locale-data` error.

## Parts Output

The v0 string result is a single formatted expression value. A future
`formatToParts` integration SHOULD preserve enough metadata for UI renderers to
distinguish at least:

- literal text from the selected CLDR pattern
- the numeric quantity replacement, when a `{0}` replacement occurred
- the selected unit, direction, style, policy, locale, and plural category

This draft does not standardize the parts shape.

## Non-Goals

The following are out of scope for v0:

- timestamp operands
- calendar-day computation using a time zone
- daylight-saving-time behavior
- relative date ranges
- absolute date fallback
- UI refresh cadence such as "next update in 30 seconds"
- localized number formatting requirements beyond substituting the computed
  integer quantity into a CLDR pattern

## Worked Examples

Input:

```text
x = -90
locale = en
style = narrow
policy = precise
numeric = always
unit = auto
```

Processing:

```text
abs = 90
unit = minute
quantity = roundHalfExpand(90 / 60) = 2
direction = past
plural = other
pattern = "{0}m ago"
output = "2m ago"
```

Input:

```text
x = -60
locale = ar
style = narrow
policy = precise
numeric = always
unit = auto
```

Processing:

```text
abs = 60
unit = minute
quantity = 1
direction = past
plural = one
pattern = "قبل دقيقة واحدة"
output = "قبل دقيقة واحدة"
```

The Arabic pattern has no `{0}` placeholder, so the pattern is returned
unchanged.
