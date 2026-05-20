# MF2 Foundation Roadmap

## Purpose

Make Mojito the best TMS for MessageFormat 2 while building the missing runtime
ecosystem deliberately enough that the result is production software, not a
convincing prototype.

The program has three tracks:

- Mojito UI: best-in-class MF2 translation, review, diagnostics, preview, and
  migration tooling.
- Runtime libraries: production-grade Python and native Swift first, with Rust
  as the parser/tooling/LSP core unless a production Rust runtime becomes a
  direct priority.
- Conformance: one shared suite that every parser, formatter, compiler, and
  runtime must pass.

## Key Decisions

- Use the official Unicode MF2 Interchange Data Model as the cross-language
  contract. Parser ASTs can differ by language; the shared model should not.
- Treat locale identifiers as structured data, not strings. Locale lookup must
  canonicalize separators/case without losing meaningful subtags such as
  `pt-PT`, because plural rules and later formatting data can differ from the
  base language fallback.
- Keep plural locale resolution narrower than general resource-bundle fallback.
  The plural package may use generated plural-specific parent overrides, but it
  must not blindly apply CLDR's general `parentLocales` data; ICU4J validates
  cases such as `pt-AO` against the generic `pt` plural rule, not the `pt-PT`
  resource parent.
- Keep parser AST, interchange model, and runtime IR separate:
  - parser AST: implementation-specific, editor-oriented, may include recovery
    nodes, source spans, trivia, and invalid content
  - interchange model: official logical message representation from the MF2 spec
  - runtime IR: optional compact/generated form for mobile or server runtime
    performance
- Build conformance fixtures before broad implementation.
- Treat `mf2-tools` and `mf2lsp` as a benchmark and possible integration target,
  not as code to copy. The current GPL-3.0-or-later license requires review
  before embedding or vendoring.
- Swift should be native and zero runtime dependency. Apps should not have to
  ship a parser unless they need dynamic runtime messages.
- Python remains a real runtime gap. Babel provides useful i18n/catalog/CLDR
  infrastructure, but not an MF2 parser/formatter/runtime.

## Architecture

```text
MF2 source
  -> implementation parser AST
  -> official MF2 Interchange Data Model
  -> conformance checks
  -> optional compiled runtime IR or generated native code
  -> formatter / formatToParts
```

For Mojito:

```text
React editor
  -> TypeScript adapter
  -> Rust/Wasm LSP or equivalent editor engine
  -> diagnostics / highlighting / completion / formatting / quick fixes
```

For Swift apps:

```text
.mf2 catalog
  -> SwiftPM build plugin or external compiler
  -> official data model / generated Swift runtime model
  -> MessageFormat2Runtime
```

## V0: Placeholders + Plurals

The first production-shaped win is intentionally small: dumb placeholders plus
real CLDR plural selection. That already beats gettext for many product strings
because translators can work with named arguments and locale-aware plural
variants without source-code branching or string concatenation.

V0 scope:

- parse simple messages with literal text, escaped braces, and named
  placeholders
- support `.input` declarations for selector annotations
- support `.match` with `:number` plural selection and `:string` exact selection
- generate CLDR cardinal and ordinal plural rules for a locale allowlist
- format from the Unicode MF2 Interchange Data Model in Rust, Swift, and Python
- ship Swift/Rust embedded runtimes without parser or JSON CLDR data when using
  generated code
- compare supported cases against ICU4J and ICU4C++ reference harnesses

V0 explicitly excludes:

- full date/time/number formatting options
- custom functions beyond the built-in minimal registry
- target-only grammatical metadata workflows
- compact runtime IR
- `format_to_parts` and markup rendering beyond preserving structure
- full editor/LSP behavior

V0 production gate: the placeholder/plural subset must be boringly reliable,
fixture-heavy, reference-compared, and faster/smaller than depending on a full
general-purpose i18n runtime for every client.

## Phase 1: Shared Conformance

Create `mf2/conformance` with:

- the official `message.json` schema from Unicode
- source-to-model fixtures
- model-to-output fixtures
- invalid-source diagnostics fixtures
- parts/markup fixtures
- plural/matcher fixtures

Rule: if a behavior is not represented in conformance, it is not production
behavior.

Conformance must include reference comparison, not only Mojito-internal
agreement. ICU4J, ICU4C++, JavaScript, and any official Unicode fixture corpus
should be run against the shared fixtures with unsupported cases and mismatches
reported explicitly.

## Phase 2: Rust Tooling Prototype

Build a clean-room Rust prototype that:

- parses the first fixture slice into the official data model
- formats simple model patterns
- emits stable diagnostic codes
- runs conformance fixtures in tests
- later becomes the CLI/compiler/LSP/Wasm candidate

The prototype should stay intentionally narrow until the conformance suite is
large enough to prevent false confidence.

## Phase 3: Mojito MF2 UI

Add MF2-aware editor surfaces:

- syntax and semantic highlighting
- diagnostics with ranges and stable codes
- format action
- variable completion and rename safety
- locale-aware preview values
- target/source structure comparison
- target-language-only variant support
- deterministic checks before AI assistance

Start in a dedicated MF2 preview/editor tool, then wire into Workbench and
text-unit detail.

## Phase 4: Native Swift

Create a zero-runtime-dependency Swift package:

- `MessageFormat2Runtime`: compiled model formatter and parts output
- `MessageFormat2Parser`: optional source parser and diagnostics
- `MessageFormat2CompilerPlugin`: build-time catalog compiler

Default app integration should ship only runtime data and formatter code. Dynamic
message parsing remains available through the optional parser target.

## Phase 5: Python

Create a Python package with:

- parser and data-model loader
- formatter and `format_to_parts`
- diagnostics with source offsets
- pytest conformance runner
- catalog/resource loading helpers
- framework adapters only after core behavior is stable

## Production Gates

Before calling any library production-ready:

- passes shared conformance fixtures
- parse -> model -> print/format behavior is stable
- invalid syntax returns diagnostics, not crashes
- fuzz/property tests do not panic
- locale/plural behavior is explicitly tested
- public API and error model are documented
- package install works in a clean project
- performance is measured on realistic catalogs
- CPU and memory profiles exist for hot paths before optimization work starts
- reference implementations have been compared for supported fixture cases
- license and dependency review is complete

## Immediate Work Items

1. Extend the shared locale-id helpers beyond the current tiny BCP47
   normalization/structural lookup: decide which aliases, available-locale
   matching, and catalog fallback policies belong outside the plural runtime.
2. Expand generated CLDR plural coverage beyond the minimal locale set: all V0
   locales, ordinal selection, sample comparisons, and package-size gates.
3. Expand placeholder/plural conformance: all V0 locales, annotated `:string`
   selectors, missing arguments, invalid selector declarations, and reference
   behavior for explicit number selector options across ICU implementations.
4. Align Rust parser fixtures with ICU requirements for annotated selectors.
5. Define small V0 runtime APIs for formatting from the Unicode model without
   shipping source parsers.
6. Add package-size and hot-loop perf checks for the V0 subset.
7. Expand the reference harness beyond ICU4J/ICU4C++ to JavaScript and the
   official Unicode test corpus.
8. After V0 stabilizes, grow the Rust prototype toward editor spans, recovery,
   formatting, and Wasm/LSP packaging.
