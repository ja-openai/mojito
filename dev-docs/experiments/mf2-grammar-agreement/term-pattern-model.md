# Term Pattern Model

This is the revised model after the first prototype review. The initial
proposal treated term metadata such as `gender`, `phonology.elides`, and
`forms.plural` as the main runtime source of truth. That is too weak for real
agreement. The preferred model is:

```text
message MF2 pattern + term MF2 form pattern + optional metadata
```

Messages describe the operation in context. Terms describe their own localized
surface forms. Metadata is retained for editor UI, validation, search,
prefill, compression, diagnostics, and fallback generation.

## Canonical Term Shape

```json
{
  "text": "épée",
  "forms": {
    "default": ".input {$usage :string}\n.input {$number :string}\n.input {$count :number}\n.match $usage $number $count\nbare singular * {{épée}}\nbare plural * {{épées}}\ndefinite singular * {{l'épée}}\ndefinite plural * {{les épées}}\nindefinite singular * {{une épée}}\nindefinite plural * {{des épées}}\ncount * one {{une épée}}\ncount * other {{{$count} épées}}\n* * * {{épée}}"
  },
  "morphology": {
    "partOfSpeech": "noun",
    "gender": "feminine",
    "number": "singular"
  }
}
```

The `forms.default` value is an MF2 pattern. It may select on any
profile-defined inputs, for example:

- `usage`: `bare`, `definite`, `indefinite`, `partitive`, `count`, or
  domain-specific usages.
- `number`: singular, plural, dual, paucal, or locale-defined values.
- `count`: the actual count, with MF2 plural-category matching.
- `case`: nominative, accusative, dative, genitive, instrumental, etc.
- `gender`, `animacy`, `nounClass`, `mutation`, `formality`, or any
  locale-profile dimension.

## Valid MF2 Matcher Syntax

The Unicode playground requires selectors to be declared first and matched as
variables:

```mf2
.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare * singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
* * * {{Schild}}
```

The earlier shorthand below is not valid current MF2 syntax:

```mf2
.match {$usage :select} {$case :select} {$number :select}
```

The public MessageFormat 2 docs show the same shape: `.input` declarations,
then `.match $variable`, with variant bodies wrapped in `{{...}}`.

## Message Usage

Messages stay small:

```mf2
Vous avez ramassé {$item :term article=definite}.
Vous avez trouvé {$count :count of=$item}.
Du hast {$item :term article=definite case=accusative} aufgehoben.
```

The formatter maps message options to a term-form context:

```json
{
  "usage": "definite",
  "case": "accusative",
  "number": "singular",
  "count": "*"
}
```

Then it evaluates the term's MF2 form pattern.

## Why Not Only JSON Fields?

JSON fields are useful, but they should not be the only runtime model.

Pros of explicit JSON fields:

- Fast to read for simple cases.
- Easy to edit with form controls.
- Easy to index and validate.
- Easy to compact into bit flags.
- Good for prefill and heuristics.

Cons of explicit JSON fields:

- Field names become language-specific quickly.
- Cross-products explode: usage x case x number x count x gender x animacy.
- It encourages heuristics like `elides=true` instead of explicit output.
- Counted forms are not always derivable from bare plurals.
- Some languages need phrase-level or construction-level forms, not just noun
  metadata.

Pros of MF2 term patterns:

- The output is explicit and translator-controlled.
- Sparse match tables avoid storing repeated values.
- The same selector model works for French, German, Russian, Arabic, Welsh, and
  domain-specific game terms.
- It can be tested in standard MF2 tooling.
- It fits the mental model translators already need for messages.

Cons of MF2 term patterns:

- Runtime must parse or precompile more than scalar metadata.
- Editor UI must help translators avoid giant raw matcher blocks.
- Validation must understand term-level inputs, not just message inputs.
- Tiny apps may pay more bytes than a few metadata flags.

## Should Everything Be MF2?

Use MF2 for anything that directly produces localized text. Use JSON fields for
facts about that text.

Recommended split:

```text
rendered form        -> MF2 term pattern
grammar facts        -> JSON metadata
editor constraints   -> locale profile JSON
compact runtime data -> generated from MF2 + metadata
```

For example, `gender=feminine` remains useful for filtering, linting, and
prefilling. But `l'épée`, `une épée`, and `3 épées` should be explicit MF2
variants when correctness matters.

## Performance Notes

Runtime hot path should not parse raw MF2 strings repeatedly.

Recommended tiers:

| Tier | Storage | Runtime path | Best for |
| --- | --- | --- | --- |
| Authoring | Nested JSON | parse on save/preview | TMS, fixtures, debugging |
| Web app | Flat KV JSON + precompiled term AST cache | parse once per locale load | normal web clients |
| Mobile compact | String pool + compiled matcher tables | no raw parser on hot path | bundle-size-sensitive apps |
| Backend | Canonical JSON or database rows + cache | parse once per deployment/catalog version | large catalogs |

Expected cost model:

- A simple metadata-only French term can fit in roughly `text + 2-8 bytes flags`
  plus key/index overhead.
- A term MF2 pattern is larger as source text, often `150-600 bytes` before
  compression for a noun with usage/count/case rows.
- Gzip/Brotli compress repeated `.input`, `.match`, and variant labels well
  across many terms.
- A compiled sparse matcher can store selector IDs, key IDs, and output string
  offsets. The compact representation is usually much smaller than the source
  pattern and does not require parsing in the render loop.

Hot-path rendering should be:

```text
message compiled AST -> resolve term ID -> compiled term matcher -> string pool output
```

Not:

```text
message string -> regex parse -> term string -> raw MF2 parse -> output
```

The current prototype intentionally uses a tiny parser so the design is easy to
inspect. Production runtimes should compile messages and term patterns.

## KV-Only Resource Access

The formatter should require only key/value reads:

```ts
type ResourceStore = {
  getString(key: string): string | undefined;
  getJson<T>(key: string): T | undefined;
  getBytes?(key: string): Uint8Array | undefined;
};
```

Nested JSON, Android `strings.xml`, Java properties, iOS catalogs, SQLite rows,
remote config, and binary packs can all implement this shape.

Example flat keys:

```text
messages/inventory.pickup/value
terms/item.sword/text
terms/item.sword/forms/default
terms/item.sword/morphology/gender
profiles/fr-v1
```

Android XML can store a JSON object or individual generated keys:

```xml
<string name="ga_term_item_sword_forms_default">.input {$usage :string}...</string>
```

That XML is generated transport. Translators should edit the canonical TMS
fields and term-pattern UI, not raw platform files.

## Open Implementation Work

- Replace regex prototypes with real MF2 parser bindings where available.
- Compile term patterns into a sparse matcher AST.
- Add locale-profile validation for allowed selector dimensions and values.
- Add TMS editor controls that generate sparse MF2 patterns instead of forcing
  translators to hand-edit matcher text.
- Add a compact binary pack once JSON proves the API.
