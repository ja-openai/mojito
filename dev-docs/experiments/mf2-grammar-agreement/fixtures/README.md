# Grammar Agreement Fixtures

These fixtures are not exhaustive language descriptions. They are minimal
interop tests for MF2 grammar-agreement extensions.

Each fixture contains:

- `messages`: MF2-like patterns using proposed grammar functions;
- `terms` or `people`: localized runtime values. Terms may include
  `forms.default`, an MF2 matcher that explicitly renders usage/case/number/count
  variants;
- `examples`: expected formatted output for conformance tests.

## Fixture Coverage

| Locale | Fixture | Main challenge |
| --- | --- | --- |
| `fr` | `fr/inventory.json` | term-level MF2 for definite articles/elision, adjective agreement |
| `fr` | `fr/game-loot.json` | term-level MF2 for counted forms, plural term forms, adjective agreement |
| `de` | `de/case-articles.json` | term-level MF2 for three genders and case-sensitive articles |
| `ru` | `ru/case-animacy.json` | case forms and animacy |
| `ar` | `ar/person-gender-number.json` | person gender/number agreement on verbs |
| `sw` | `sw/noun-class.json` | noun-class agreement |
| `ja` | `ja/classifier-count.json` | classifier selection for count phrases |
| `ko` | `ko/formality.json` | politeness/formality-controlled verb forms |
| `cy` | `cy/mutation.json` | mutation triggered by message construction |
| negative | `negative/*.json` | expected validation diagnostics |

## Test Runner Shape

A future runtime conformance runner can load each fixture and assert:

```text
format(message.value, fixture args, fixture terms/people) == example.output
```

It should also validate inferred requirements:

```text
message pattern -> required term usages -> term forms/default + metadata
```

`expects` is included in older fixtures as an explicit assertion, but
implementations should derive most requirements from message functions plus the
locale profile. New fixtures should prefer term form coverage assertions over
hard-coding metadata fields as the source of truth.

The Python prototype currently implements positive examples for `fr`, `de`,
`ru`, `ar`, `sw`, `ja`, `ko`, and `cy`. Negative fixtures assert structured
diagnostics for missing morphology, invalid term patterns, invalid argument
type, and unsupported option values.
