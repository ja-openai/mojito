# MF2 Catalog Demo

This directory contains a tiny parser-free catalog demo. The catalog stores the
official MF2 model directly; each runtime exposes a local `translate` helper that
does only:

```text
message id + locale + arguments -> formatted string
```

Run the language demos:

```sh
(cd ../rust/mojito-mf2 && cargo run --example translate_demo)
(cd ../rust/mojito-mf2 && cargo run --example inline_translate_demo)
(cd ../swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd ../python && python3 examples/translate_demo.py)
(cd ../java && sh run.sh showcase)
```

This is not the final public API. It is a sanity check for the app-facing shape:
resource bundle lookup should be separate from parsing, compiling, and runtime
formatting.

The Rust and Java `inline_translate_demo` commands intentionally parse MF2
source strings at runtime. They are useful for demos and dynamic messages, but
the catalog demos remain the preferred shape for production resource bundles.
The Java `showcase` command runs both shapes and also prints parts output plus
fallback formatting, which are easier to inspect in Java's object output.

The catalog includes messages that use `:currency`. Java's production default
registry formats those through the JDK platform formatter. Shared demos may use
sample or fixture registries to keep output stable across runtimes, including
app-specific functions such as `rawType` and `relativeTime` and a sample
`:currency` formatter. Those handlers are demo support, not portable production
runtime behavior. See the Java `platform-demo` command for locale-aware number,
percent, currency, date, time, and datetime output, and
`../reference/fixtures/currency-simple-vs-icu4j.json` for diagnostic comparisons
against ICU4J currency formatting.

The demos also include a `file.saved` case that requests default bidi isolation
around a Hebrew filename embedded in English text. The isolate controls are
invisible in most terminals, so the demos also print an `.escaped` line with
the actual `\u2068` FSI and `\u2069` PDI characters. This demonstrates
isolation only; production filename, URL, and code-like formatting still needs
value-direction metadata from the formatter or caller instead of relying only on
first-strong-character auto direction.
