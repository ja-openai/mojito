# JavaScript MF2 Core

Zero-dependency ESM prototype for Mojito's native JavaScript MessageFormat 2
core.

This package mirrors the Rust/Java prototype boundaries:

- `parser.js`: MF2 source to the Unicode MF2 Interchange Data Model
- `runtime.js`: parser-free formatting from the model
- `formatMessageToParts`: first-class structured output for React/HTML/native UI
  renderers
- `conformance.js`: shared fixture runner

The package root exports the common parser/runtime API. Subpath exports expose
the stable internal boundaries used by wrappers and tooling:

- `@mojito-mf2/core/parser`
- `@mojito-mf2/core/runtime`
- `@mojito-mf2/core/plural-rules`
- `@mojito-mf2/core/locale-key`
- `@mojito-mf2/core/errors`

Plural selection uses generated CLDR cardinal/ordinal rules shared with the
other native runtimes.

Run:

```sh
npm run generate:plurals
npm run check
npm run demo
npm run bench:format
npm run bench:parse
npm run bench:plural
```
