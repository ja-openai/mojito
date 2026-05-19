# MessageFormat2 Python

Zero-dependency Python runtime starter for the MF2 foundation.

## Shape

The Python package keeps the runtime layers separate:

- `mf2_runtime.formatter`: model-to-string formatting and selector matching
- `mf2_runtime.plural`: small runtime wrapper around generated CLDR plural code
- `mf2_runtime.generated_plural_rules`: generated CLDR cardinal/ordinal rules
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
  function pass-through, markup-as-parts-stripped string output, and exact-match
  selectors with catch-all fallback
- cardinal plural category selection for the initial locale set

Planned:

- source parser and diagnostics
- `format_to_parts`
- locale-sensitive number/date/plural behavior
- pytest-compatible conformance runner once the core package shape is stable

Run:

```sh
python3 -m mf2_runtime.conformance ../conformance/fixtures/source-to-model
python3 examples/translate_demo.py
python3 -m mf2_runtime.benchmark ../conformance/fixtures/source-to-model
```
