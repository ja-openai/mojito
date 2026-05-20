# MessageFormat2 Python

Zero-dependency Python runtime starter for the MF2 foundation.

## Shape

The Python package keeps the runtime layers separate:

- `mf2_runtime.formatter`: model-to-string formatting and selector matching
- `mf2_runtime.plural`: small runtime wrapper around generated CLDR plural code
- `mf2_runtime.generated_plural_rules`: generated CLDR cardinal/ordinal rules
- `mf2_runtime.locale_key`: tiny BCP47-first string helpers and structural lookup
- `mf2_runtime.errors`: public runtime errors
- `mf2_runtime.model`: compatibility facade for the early API
- `mf2_runtime.conformance`: shared fixture runner
- `mf2_runtime.benchmark` and `mf2_runtime.profiler`: runtime measurement tools
- `examples/translate_demo.py`: tiny `translate(id, locale, args)` catalog demo

There is intentionally no source parser in the runtime path yet. Catalogs can
load the official Unicode MF2 model directly, and a future parser/compiler
package can produce the same model without becoming a formatter dependency.

Current scope:

- load the official MF2 Interchange Data Model from dictionaries/JSON
- format the shared conformance fixture slice
- support declarations, local variables, variable/literal expressions, basic
  function pass-through, markup-as-parts-stripped string output, exact-match
  selectors, and `:number select=exact` with catch-all fallback
- expose `format_message_to_parts` for text, expression, and markup boundary
  output in the supported runtime slice, preserving expression/markup attributes
  for UI renderers
- reject invalid model structure for duplicate declarations, select variant key
  arity, duplicate variants, and missing fallback variants
- cardinal and ordinal plural category selection for every generated CLDR plural
  locale
- BCP47 locale canonicalization, underscore compatibility, extension stripping,
  and structural fallback for catalog lookup. Plural rules keep their own
  string-only lookup so they do not depend on a locale object.

Planned:

- source parser and diagnostics
- locale-sensitive number/date formatting and richer locale negotiation
- pytest-compatible conformance runner once the core package shape is stable

Run:

```sh
python3 -m mf2_runtime.conformance ../conformance/fixtures/source-to-model
python3 examples/translate_demo.py
python3 -m mf2_runtime.benchmark ../conformance/fixtures/source-to-model
```
