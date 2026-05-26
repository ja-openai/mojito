# Mojito MessageFormat2 Python

Zero-dependency Python parser/formatter package for the MF2 foundation.

## Shape

The Python package uses modern packaging conventions:

- `pyproject.toml`: package metadata and build backend
- `src/mojito_mf2`: importable package
- `tests`: unit and package-boundary tests
- `tools`: conformance, benchmark, and profiler entry points that are not part
  of the installed package API
- `examples/translate_demo.py`: tiny `translate(id, locale, args)` catalog demo

The parser and formatter layers stay separate inside `mojito_mf2`:

- `mojito_mf2.formatter`: model-to-string formatting and selector matching
- `mojito_mf2.parser`: source-to-model parser and diagnostics for the supported
  slice
- `mojito_mf2.errors`: public formatter errors
- `mojito_mf2.model`: public `TypedDict` and type aliases for MF2 models,
  formatted parts, arguments, and function annotations
- `mojito_mf2.babel`: optional Babel-backed formatter registry, loaded only
  when explicitly imported

Internal modules such as `_locale_key`, `_plural`, and `_cldr_plural_rules`
back formatter behavior and test tooling, but they are intentionally outside
the stable package contract.

The package root exposes the stable app-facing API only:

- `parse_to_model`
- `format_message`
- `format_message_to_parts`
- `FunctionRegistry`, `FunctionCall`, `FunctionMatch`
- `MF2Error`, `MF2ParseDiagnostic`, `ParseResult`
- `FormatResult`, `PartsResult`, `MF2RecoveryContext`
- `MF2MessageModel`, `MF2FormattedPart`, `MF2Arguments`, and related model
  aliases from `mojito_mf2.model`

The package ships `py.typed`; type checkers consume the inline annotations and
the public model aliases directly from the installed package. `sh run.sh
typecheck` runs a package-boundary mypy fixture that exercises the root exports,
model typing, formatted parts, registry callbacks, and recovery callbacks.

`format_message` returns `FormatResult(value, errors)` with `ok` and
`has_errors` convenience properties. `format_message_to_parts` returns
`PartsResult(parts, errors)` with the same status properties. By default,
formatting recovers with Unicode MF2 visible fallback values such as `{$name}`
while preserving diagnostics in `errors`. Applications can pass
`on_missing_argument` and `on_format_error` callbacks to replace the local
fallback value while still collecting the diagnostic.

`FunctionRegistry.portable()` is the dependency-free production registry for
behavior implemented consistently across runtimes: `:string`, `:offset`, CLDR
plural matching, exact numeric selector matching, and unlocalized `:number`,
`:integer`, and `:percent` formatting. The numeric formatters are intentionally
not locale-pretty: they do not localize digits, grouping, separators, or
unit/currency display.

`FunctionRegistry.defaults()` currently returns the portable registry. Babel
locale-pretty formatting is explicit opt-in so the core package remains
stdlib-only:

```sh
pip install "mojito-mf2[babel]"
```

```python
from mojito_mf2 import format_message, parse_to_model
from mojito_mf2.babel import babel_function_registry

message = parse_to_model("Due {$delta :relativeTime unit=day}").model
result = format_message(
    message,
    {"delta": -3},
    locale="fr",
    functions=babel_function_registry(),
)
```

The Babel registry provides locale-pretty `:number`, `:percent`, `:integer`,
`:currency`, `:date`, `:time`, `:datetime`, and `:relativeTime` formatters where
Babel supports them. Importing `mojito_mf2` does not import or require Babel;
importing `mojito_mf2.babel` without the optional dependency fails with an
install hint. Fixture-only handlers for Unicode official tests and demos live
under `tools`, `tests`, or `examples`, not in the production formatter.

Catalogs can load the official Unicode MF2 model directly or parse source
messages into the same model. A future package split can keep source parsing out
of parser-free formatter deployments.

Current scope:

- load the official MF2 Interchange Data Model from dictionaries/JSON
- parse MF2 source into the official model with stable diagnostics for invalid
  source fixtures
- format the shared conformance fixture slice
- support declarations, local variables, variable/literal expressions, basic
  function registry pass-through, markup-as-parts-stripped string output,
  exact-match selectors, ranked multi-selector matching, `:offset` locals, and
  fixed numeric selector keys with catch-all fallback
- expose both the rendered string value and raw Python argument value to custom
  function callbacks
- expose custom selector callbacks through `FunctionRegistry.with_selector`
- expose `format_message_to_parts` for text, expression, and markup boundary
  output in the supported formatter slice, preserving expression/markup attributes
  for UI renderers
- expose `format_message` and `format_message_to_parts` for spec-style fallback
  output plus collected formatting errors
- support opt-in `bidi_isolation="default"` string output around expression
  values
- reject invalid model structure for duplicate declarations, select variant key
  arity, duplicate variants, and missing fallback variants
- cardinal and ordinal plural category selection for every generated CLDR plural
  locale
- BCP47 locale canonicalization, underscore compatibility, extension stripping,
  and structural fallback for catalog lookup. Plural rules keep their own
  string-only lookup so they do not depend on a locale object.

Planned:

- richer locale negotiation

Run:

```sh
sh run.sh conformance
sh run.sh test
sh run.sh typecheck
sh run.sh demo
sh run.sh babel-demo
sh run.sh bench
sh run.sh bench-parse
```
