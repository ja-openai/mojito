# Multi-Runtime Implementation Plan

The Python loader is a spike. The actual extension needs aligned
implementations for JavaScript/TypeScript, Java, and Rust.

## Shared Contract

All runtimes consume:

- canonical JSON fixtures;
- locale grammar profiles;
- MF2 grammar function calls;
- term-level MF2 `forms.default` patterns;
- resource bundle API semantics.

All runtimes produce:

- formatted output;
- validation diagnostics;
- inferred required term usages and morphology;
- optional compact/binary export support depending on runtime role.

## Shared Test Data

Use `fixtures/**` as conformance inputs.

Test vector format:

```json
{
  "messageId": "inventory.pickup",
  "args": {
    "item": "item.sword"
  },
  "output": "Vous avez ramassé l'épée."
}
```

Each runtime should expose a conformance command:

```bash
js:   npm test -- grammar-fixtures
java: mvn test -Dtest=GrammarAgreementFixtureTest
rust: cargo test grammar_fixtures
```

## JavaScript / TypeScript

Primary uses:

- web/runtime formatting;
- MF2 editor preview;
- TMS UI validation;
- browser-side bundle loading.

Packages:

```text
@mojito-mf2/grammar-core
@mojito-mf2/grammar-fr
@mojito-mf2/grammar-fixtures
```

Core modules:

```text
ResourceStore
GrammarResourceResolver
GrammarFormatter
LocaleGrammarAdapter
TermFormPattern
CompiledTermMatcher
Diagnostics
CompactJsonStore
NestedJsonStore
```

API:

```ts
const formatter = new GrammarFormatter({
  locale: "fr",
  store,
  adapters: [frAdapter()],
});

formatter.format("inventory.pickup", {
  item: termRef("item.sword"),
});
```

Editor needs:

- parse grammar function nodes;
- show structured controls for options;
- call `requiredUsages()` / `requiredFeatures()` live;
- edit term-level MF2 matchers through table controls;
- preview fixtures and current message.

Current prototype:

```bash
node dev-docs/experiments/mf2-grammar-agreement/js/grammar_fixture_runner.mjs
```

passes the shared fixture suite, including positive examples for `fr`, `de`,
`ru`, `ar`, `sw`, `ja`, `ko`, and `cy`, plus negative diagnostic examples. This
is dependency-free JavaScript for portability; a real editor/runtime package
should be TypeScript with typed resource values and diagnostics.

## Java

Primary uses:

- Mojito backend validation;
- import/export services;
- server-side preview;
- Android-adjacent reference implementation.

Packages:

```text
com.box.l10n.mojito.grammar
com.box.l10n.mojito.grammar.adapter
com.box.l10n.mojito.grammar.bundle
```

Core interfaces:

```java
interface ResourceStore {
  Optional<String> getString(String key);
  Optional<JsonNode> getJson(String key);
  Optional<byte[]> getBytes(String key);
}

interface GrammarResourceResolver {
  MessageValue message(String id, Locale locale);
  TermValue term(String id, Locale locale);
  PersonValue person(String id, Locale locale);
  LocaleGrammarProfile profile(Locale locale);
}

interface LocaleGrammarAdapter {
  String formatTerm(TermValue value, TermOptions options);
  List<RequiredFeature> requiredFeatures(String function, Map<String, Object> options);
}

interface TermFormCompiler {
  CompiledTermMatcher compile(String mf2Pattern, LocaleGrammarProfile profile);
}
```

Mojito services:

- `GrammarTermService`
- `GrammarValidationService`
- `GrammarPackExportService`
- `GrammarSuggestionService`

Current status:

- Java 21 is available locally.
- Mojito `common` already has Jackson dependencies.
- The Java implementation should be added as real `common` module code/tests
  rather than a standalone docs script with a fake JSON parser.
- See [java/README.md](java/README.md) for the proposed package layout and
  fixture-test shape.

## Rust

Primary uses:

- compact binary pack compiler;
- tiny runtime;
- optional WASM core for JS/editor;
- conformance oracle for memory-sensitive formats.

Crates:

```text
mf2_grammar_core
mf2_grammar_profiles
mf2_grammar_pack
mf2_grammar_fixture_tests
```

Core traits:

```rust
trait ResourceStore {
    fn get_str(&self, key: &str) -> Option<&str>;
    fn get_json(&self, key: &str) -> Option<serde_json::Value>;
    fn get_bytes(&self, key: &str) -> Option<&[u8]>;
}

trait LocaleGrammarAdapter {
    fn format_term(&self, value: &TermValue, options: &TermOptions) -> Result<String>;
    fn required_features(&self, function: &str, options: &Options) -> Vec<RequiredFeature>;
}
```

Binary pack compiler:

```text
canonical JSON -> profile-aware compact records + compiled term matchers -> .gagp
```

Binary pack reader:

```text
zero-copy string pool, term records, and sparse matcher tables where possible
```

Current status:

- Rust 1.88 / Cargo 1.88 are available locally.
- This repo is not a Cargo workspace, so no crate was added yet.
- See [rust/README.md](rust/README.md) for crate layout, trait shape, and the
  `.gagp` binary pack layout.
- The next Rust step is a real `mf2_grammar_pack` crate that converts canonical
  JSON fixtures to `.gagp` and re-runs conformance through a binary resolver.

## Implementation Order

1. Normalize fixture schema around `forms.default` and add negative term-pattern
   fixtures.
2. Implement JavaScript core first for editor/runtime feedback.
3. Implement Java backend validator/exporter using the same fixtures.
4. Compile term form patterns in Rust once JSON runtime semantics stabilize.
4. Implement Rust compact pack compiler/reader.
5. Add WASM option only if Rust pack logic should be reused in the editor.
6. Add CI job that runs all fixture suites across all implementations.

## Risks

- Function semantics drifting between runtimes.
- Locale adapters becoming too ad hoc.
- Compact/binary pack format freezing too early.
- TMS users seeing too much raw grammar complexity.
- MF2 function syntax changing upstream.

Mitigation:

- fixtures are normative;
- locale profiles declare requirements;
- compact formats are generated artifacts;
- canonical JSON stays the authoring source;
- experimental namespace until stable.
