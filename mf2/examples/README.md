# MF2 Catalog Demo

This directory contains a tiny parser-free catalog demo. The catalog stores the
official MF2 model directly; each runtime exposes a local `translate` helper that
does only:

```text
message id + locale + arguments -> formatted string
```

Run the language demos:

```sh
(cd ../rust/mf2-prototype && cargo run --example translate_demo)
(cd ../rust/mf2-prototype && cargo run --example inline_translate_demo)
(cd ../swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd ../python && python3 examples/translate_demo.py)
```

This is not the final public API. It is a sanity check for the app-facing shape:
resource bundle lookup should be separate from parsing, compiling, and runtime
formatting.

The Rust and Java `inline_translate_demo` commands intentionally parse MF2
source strings at runtime. They are useful for demos and dynamic messages, but
the catalog demos remain the preferred shape for production resource bundles.

The catalog includes a deliberately narrow `:currency` demo function. It is
dependency-free and useful for showing how function options flow through the
model, but it is not a CLDR-complete replacement for production number and
currency formatting. See `../reference/fixtures/currency-simple-vs-icu4j.json`
for examples that separate current demo matches from known ICU4J divergences.
