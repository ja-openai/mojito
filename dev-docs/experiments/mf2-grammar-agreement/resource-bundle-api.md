# Grammar Resource Bundle API

This document specifies the resource layer that backs grammar-aware MF2
formatting. The key design constraint is that the formatter must work on top of
dumb storage: JSON files, Android `strings.xml`, iOS resources, database rows,
remote config, or compact binary packs.

## Design Principle

The formatter does not know storage. It knows resolved values:

```text
message pattern
term value
person value
locale grammar profile
```

Storage adapters expose key/value reads. Resolvers turn those reads into typed
runtime values.

```text
ResourceStore -> GrammarResourceResolver -> MF2 formatter functions
```

## Resource Store

Minimal API:

```ts
type ResourceStore = {
  getString(key: ResourceKey): string | undefined;
  getJson<T = unknown>(key: ResourceKey): T | undefined;
  getBytes?(key: ResourceKey): Uint8Array | undefined;
  has?(key: ResourceKey): boolean;
};

type ResourceKey = string;
```

The store is intentionally boring. It should map cleanly to:

- nested JSON;
- flattened JSON;
- Android resource names;
- Java properties;
- iOS string catalogs;
- SQL rows;
- binary pack indexes;
- remote key/value config.

## Canonical Key Space

Keys use slash-separated segments in the abstract model:

```text
messages/{messageId}/value
messages/{messageId}/comment
terms/{termId}
terms/{termId}/text
terms/{termId}/forms/default
terms/{termId}/morphology
terms/{termId}/morphology/gender
terms/{termId}/morphology/number
people/{personId}
people/{personId}/displayName
people/{personId}/morphology/gender
profiles/{profileId}
```

Platform exporters may transform keys:

```text
terms/item.sword/text
-> ga_term_item_sword_text
```

The transformation must be reversible or recorded in an index.

## Resolved Runtime Values

Term:

```ts
type TermValue = {
  id: string;
  text: string;
  forms?: {
    default?: string | CompiledMf2Pattern;
    [name: string]: string | CompiledMf2Pattern | undefined;
  };
  morphology: Morphology;
  source?: ResourceProvenance;
};
```

Person:

```ts
type PersonValue = {
  id: string;
  displayName: string;
  morphology: PersonMorphology;
};
```

Message:

```ts
type MessageValue = {
  id: string;
  pattern: string;
  locale: string;
  profile: string;
};
```

Resolver:

```ts
type GrammarResourceResolver = {
  message(id: string, locale: string): MessageValue;
  term(id: string, locale: string): TermValue;
  person(id: string, locale: string): PersonValue;
  profile(locale: string): LocaleGrammarProfile;
};
```

## Storage Shapes

### Nested JSON

Best for authoring, TMS interchange, tests:

```json
{
  "messages": {
    "inventory.pickup": {
      "value": "Vous avez ramassé {$item :term article=definite}."
    }
  },
  "terms": {
    "item.sword": {
      "text": "épée",
      "forms": {
        "default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{l'épée}}\n* * {{épée}}"
      },
      "morphology": {
        "gender": "feminine"
      }
    }
  }
}
```

### Flattened KV JSON

Best for platform stores that naturally handle string keys:

```json
{
  "messages/inventory.pickup/value": "Vous avez ramassé {$item :term article=definite}.",
  "terms/item.sword/text": "épée",
  "terms/item.sword/forms/default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{l'épée}}\n* * {{épée}}",
  "terms/item.sword/morphology/gender": "feminine"
}
```

### Compact JSON

Best when JSON must ship but size matters:

```json
{
  "l": "fr",
  "p": "fr-v1",
  "m": {
    "inventory.pickup": "Vous avez ramassé {$item :term article=definite}."
  },
  "t": {
    "item.sword": ["épée", 2, 17]
  }
}
```

The tuple shape is profile-defined. A compact profile may reference a compiled
term-form table instead of embedding raw MF2 source. For a tiny metadata-only
fallback, `fr-v1` may still use:

```text
[text, flags16, plural?]
```

Terms that need explicit sparse selectors should either keep canonical
`forms.default` or point to a compiled matcher record. The compact format is
allowed to be hybrid:

```json
{
  "t": {
    "item.sword": ["épée", 2, 17],
    "adjective.new": {
      "text": "nouveau",
      "forms": {
        "default": ".input {$gender :string}\n.input {$number :string}\n.match $gender $number\nfeminine singular {{nouvelle}}\n* * {{nouveau}}"
      },
      "morphology": {
        "partOfSpeech": "adjective"
      }
    }
  }
}
```

### Android `strings.xml`

Valid generated transport:

```xml
<string name="ga_message_inventory_pickup">Vous avez ramassé {$item :term article=definite}.</string>
<string name="ga_term_item_sword">{"text":"épée","forms":{"default":".input {$usage :string}..."}}</string>
```

or:

```xml
<string name="ga_term_item_sword_text">épée</string>
<string name="ga_term_item_sword_forms_default">.input {$usage :string}...</string>
<integer name="ga_term_item_sword_flags">2</integer>
```

The XML is generated output. TMS/editor tooling should edit canonical JSON
objects, not these generated resources.

### Binary Pack

Best for large catalogs or mobile bundle pressure:

```text
Header
Key index
String pool
Message table
Term table
Form table
Profile table
```

Hot term record:

```text
termKeyIndex    varint or 64-bit hash
textOffset      varint
flags           16/32/64 bits, profile-defined
formMatcherOffset varint, 0 if no explicit form matcher
```

For `fr-v1`, a metadata-only fallback can be:

```text
textOffset + flags16
```

The preferred explicit path is:

```text
textOffset + flags16 + compiled sparse matcher offset
```

Optional source MF2 is not needed at runtime if the matcher is precompiled.

## Locale Profiles

A locale profile defines:

- supported grammar functions;
- required morphology fields per function/options;
- enum values;
- compact flag layout;
- fallback rules;
- form table keys.

Example:

```json
{
  "id": "fr-v1",
  "locale": "fr",
  "features": ["usage", "gender", "number", "article", "plural"],
  "flags": {
    "gender": { "bits": [0, 1], "values": ["unknown", "masculine", "feminine"] },
    "number": { "bits": [2, 3], "values": ["unknown", "singular", "plural", "invariant"] },
    "hasExplicitForms": { "bit": 4 }
  }
}
```

Profiles are what keep the core model open-ended without making every runtime
hardcode every language.

## Resolver Requirements

Resolvers must:

- resolve whole objects when available;
- synthesize objects from flat fields when needed;
- decode compact tuples and binary records using the locale profile;
- preserve unknown extension fields in canonical JSON mode;
- expose missing fields distinctly from `unknown` values.

Missing:

```text
field absent; formatter may error or fallback
```

Unknown:

```text
field known to be unknown; formatter should use neutral/unknown policy
```

This distinction is critical for person gender and incomplete TMS data.

## Prototype Exporter

The prototype includes an exporter that proves canonical fixtures can be
converted to dumb storage shapes and round-tripped through the same formatter:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/resource_bundle_exporter.py \
  dev-docs/experiments/mf2-grammar-agreement/fixtures/fr/inventory.json \
  --out-dir /private/tmp/mf2-grammar-export
```

It currently supports:

- flattened KV JSON for any fixture;
- `fr-v1` compact tuple JSON for noun terms;
- canonical fallback objects inside compact JSON for complex terms;
- round-trip validation against fixture examples.
