# MF2 Grammar Agreement Implementation Checklist

This checklist turns the proposal into work items for MF2 runtimes, editors, and
TMS integration.

## Runtime

- Add a morphology value type for terms and people.
- Add term/person resolver hooks:
  - `resolveTerm(termId, locale) -> TermValue`
  - `resolvePerson(personId, locale) -> PersonValue`
- Add experimental grammar functions:
  - `:term`
  - `:article`
  - `:agree`
  - `:pronoun`
  - `:verb`
  - `:selectMorph`
- Implement locale adapter interface:

```ts
type LocaleGrammarAdapter = {
  formatTerm(value: TermValue, options: TermOptions): string;
  formatArticle(value: TermValue, options: ArticleOptions): string;
  agree(value: TermValue, target: TermValue | PersonValue, options: AgreeOptions): string;
  pronoun(value: PersonValue, options: PronounOptions): string;
  verb(value: TermValue, target: TermValue | PersonValue, options: VerbOptions): string;
  requiredFeatures(functionName: string, options: Record<string, unknown>): string[];
};
```

- Start with `fr-v1`:
  - definite/indefinite article;
  - elision;
  - gender/number adjective form selection;
  - plural form if provided.

## Language Targets

The Python loader is only a fixture/conformance prototype. Production work
should target the runtimes where MF2 libraries are expected to run.

### JavaScript / TypeScript

- Primary browser/web runtime and editor integration target.
- API shape:

```ts
type ResourceStore = {
  getString(key: string): string | undefined;
  getJson<T = unknown>(key: string): T | undefined;
  getBytes?(key: string): Uint8Array | undefined;
};

type GrammarFormatter = {
  format(messageId: string, args: Record<string, unknown>): string;
};
```

- Support stores:
  - nested JSON;
  - flattened JSON key/value;
  - compact JSON tuple pack;
  - binary pack via `ArrayBuffer`.
- Run the same fixtures in unit tests.

### Java

- Primary Mojito/backend and Android-adjacent target.
- API shape:

```java
interface ResourceStore {
  Optional<String> getString(String key);
  Optional<JsonNode> getJson(String key);
  Optional<byte[]> getBytes(String key);
}

interface GrammarFormatter {
  String format(String messageId, Map<String, Object> args);
}
```

- Support stores:
  - Jackson-backed nested JSON;
  - database/key-value rows;
  - Android resource adapter if used outside Mojito;
  - binary pack via `ByteBuffer`.
- Integrate with Mojito import/export and validation services.

### Rust

- Primary compact runtime/compiler target.
- API shape:

```rust
trait ResourceStore {
    fn get_str(&self, key: &str) -> Option<&str>;
    fn get_json(&self, key: &str) -> Option<serde_json::Value>;
    fn get_bytes(&self, key: &str) -> Option<&[u8]>;
}

trait GrammarFormatter {
    fn format(&self, message_id: &str, args: &Args) -> Result<String, Error>;
}
```

- Support stores:
  - `serde_json` nested bundle;
  - flattened map;
  - zero-copy binary pack;
  - WASM export for web/editor use if useful.
- Rust is the best place to prototype the compact binary pack and shared
  conformance runner.

### Shared Conformance

- Fixtures under `fixtures/**` are the source of truth.
- Every language implementation must:
  - load canonical JSON fixtures;
  - format every `examples[]` entry;
  - report inferred morphology requirements;
  - support negative validation tests once added.

## Validation

- Parse each message and identify grammar function calls.
- Infer required morphology from function + options + locale profile.
- Validate runtime argument types:
  - `:term` receives a term;
  - `:person`/`:pronoun` receives a person;
  - `:agree with=$x` references an available target.
- Validate locale support:
  - function exists;
  - option values are supported;
  - fallback policy is explicit for missing data.
- Report actionable TMS/editor errors:
  - missing `forms.default` for a required term usage;
  - invalid term matcher selector or key;
  - missing metadata required by fallback-only rendering;
  - missing case form;
  - ambiguous term ID;
  - unsupported locale adapter feature.

## TMS

- Store localized messages as MF2 patterns.
- Store terms separately from messages.
- Add locale-profile-driven term form controls to the term editor.
- Generate sparse `forms.default` MF2 matchers from those controls.
- Support machine suggestions:
  - dictionary lookup;
  - suffix/rule guess;
  - LLM suggestion;
  - project-corpus inference.
- Store provenance:
  - `source`;
  - `confidence`;
  - `reviewStatus`;
  - `reviewer`;
  - `lastUpdated`.
- Validate all messages before export.

## Resource Bundles

- Support canonical nested JSON for authoring/interchange.
- Define storage-independent resource store API:

```ts
type ResourceStore = {
  getString(key: string): string | undefined;
  getJson<T = unknown>(key: string): T | undefined;
  getBytes?(key: string): Uint8Array | undefined;
};
```

- Define stable resource property addresses:
  - `message.{messageId}.value`
  - `term.{termId}.text`
  - `term.{termId}.forms.default`
  - `term.{termId}.morphology.gender`
  - `person.{personId}.displayName`
  - `person.{personId}.morphology.gender`
- Add resolvers that map store properties into MF2 runtime values:

```ts
type GrammarResourceResolver = {
  messagePattern(messageId: string, locale: string): string;
  term(termId: string, locale: string): TermValue;
  person(personId: string, locale: string): PersonValue;
};
```

- Support compact JSON export:

```json
{
  "l": "fr",
  "m": {
    "inventory.pickup": "Vous avez ramassé {$item :term article=definite role=object}."
  },
  "t": {
    "item.sword": {
      "text": "épée",
      "forms": {
        "default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{l'épée}}\n* * {{épée}}"
      }
    }
  }
}
```

- Validate generated storage shapes with:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/resource_bundle_exporter.py \
  dev-docs/experiments/mf2-grammar-agreement/fixtures/fr/inventory.json \
  --out-dir /private/tmp/mf2-grammar-export
```

- Support platform key/value export:
  - Android `strings.xml` value containing compact JSON;
  - individual generated keys where typed resources are useful;
  - assets for large term packs.
- Support binary pack export:
  - string pool;
  - message index;
  - term index;
  - compact flags;
  - compiled sparse term matcher table.

## Editor

- Show grammar functions as structured chips, not raw syntax only.
- Explain inferred requirements:
  - `article=definite` requires a term form for `usage=definite`.
- Show term forms and metadata inline when a message references a term.
- Warn when translator text uses a raw string where a term is expected.
- Provide fixture-based preview for each locale adapter.

## Conformance

- Load fixtures from `fixtures/**`.
- Treat schemas in `schema/**` as the shared data contract:
  - `grammar-bundle.schema.json`
  - `grammar-diagnostic.schema.json`
  - `locale-profile.schema.json`
- Treat profiles in `profiles/**` as the locale-specific validation contract.
- Run the prototype conformance loader:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/grammar_bundle_loader.py
```

- For each example:
  - resolve args;
  - format message;
  - compare expected output;
  - validate inferred requirements against `expects`.
- Add negative tests:
  - missing morphology with `fallback=error`;
  - unsupported case option;
  - ambiguous term ID;
  - unknown function.

Current prototype status:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/grammar_bundle_loader.py --include-planned
```

passes positive fixtures for `fr`, `de`, `ru`, `ar`, `sw`, `ja`, `ko`, and
`cy`, plus negative diagnostic fixtures for missing morphology, raw strings used
as terms, and unsupported option values.

JavaScript prototype status:

```bash
node dev-docs/experiments/mf2-grammar-agreement/js/grammar_fixture_runner.mjs
```

passes the same fixture suite.

Profile requirement validation:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/profile_requirement_validator.py
```

checks that fixture `expects` blocks line up with locale profile requirements
derived from `function + options`.
