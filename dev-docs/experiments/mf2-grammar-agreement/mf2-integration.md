# MF2 Integration Semantics

This document defines how grammar resources integrate with MF2 runtimes. It is
intended to be implementable in JavaScript, Java, and Rust.

## Runtime Inputs

Formatting receives:

```ts
format(messageId, args, context)
```

Where:

```ts
type Args = Record<string, RuntimeArg>;

type RuntimeArg =
  | string
  | number
  | TermRef
  | PersonRef
  | TermValue
  | PersonValue;

type TermRef = { type: "term"; id: string };
type PersonRef = { type: "person"; id: string };
```

The formatter resolves `TermRef`/`PersonRef` through the
`GrammarResourceResolver`. Raw strings are allowed only for functions that do
not require morphology.

Terms may themselves contain MF2 form patterns:

```ts
type TermValue = {
  id: string;
  text: string;
  forms?: {
    default?: string | CompiledMessageFormatPattern;
  };
  morphology?: Record<string, unknown>;
};
```

When `forms.default` exists, it is the preferred source of rendered term text.
Locale adapters and metadata fallbacks are secondary.

## Function Namespace

Use an experimental namespace until standardized:

```text
:xg:term
:xg:article
:xg:agree
:xg:pronoun
:xg:verb
:xg:selectMorph
:xg:count
```

Fixtures currently use short names for readability. Implementations should
support aliases during experimentation.

## Function Semantics

### `:xg:term`

Input:

```text
TermRef | TermValue
```

Options:

```text
article
case
number
role
definiteness
fallback
```

Output:

```text
localized term text, possibly with article/form inflection
```

Resolution steps:

1. Resolve term value.
2. Convert options into a term-form context:

```json
{
  "usage": "definite",
  "case": "accusative",
  "number": "singular",
  "count": "*"
}
```

3. If `term.forms.default` exists, evaluate that MF2 pattern with the context.
4. Otherwise apply locale adapter fallback logic.
5. If required form data or morphology is missing, apply fallback policy.

Example term form:

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

### `:xg:article`

Input:

```text
TermRef | TermValue
```

Output:

```text
article/determiner only
```

This exists because some translators need explicit control over where the
article appears in the sentence.

### `:xg:agree`

Input:

```text
TermRef | TermValue for the word to inflect
```

Required option:

```text
with=$target
```

Output:

```text
localized form agreeing with target
```

Example:

```text
{$new :xg:agree with=$item} {$item :xg:term}
```

The value being inflected can be an adjective term, participle term, predicate
term, or locale-defined agreement resource.

### `:xg:count`

Input:

```text
number
```

Required option:

```text
of=$term
```

Output:

```text
localized count phrase
```

Example:

```text
Vous avez trouvé {$count :xg:count of=$item}.
```

For French, the count phrase should usually come from the term pattern:

```text
une potion
3 potions
```

For richer languages, this may need plural category, case, classifier, and noun
forms.

### `:xg:pronoun`

Input:

```text
PersonRef | PersonValue
```

Options:

```text
case
person
number
formality
fallback
```

Must support unknown/neutral gender.

### `:xg:verb`

Input:

```text
verb term
```

Required option:

```text
with=$subject
```

Options:

```text
tense
mood
aspect
polarity
formality
```

This should initially select from explicit forms. Broad automatic conjugation is
out of scope for early implementations.

### `:xg:selectMorph`

Input:

```text
TermRef | PersonRef | morphology value
```

Options:

```text
feature=gender|number|case|animacy|nounClass|...
```

Output:

```text
selector key
```

Used with MF2 `.match`.

## Validation Semantics

Validation has two phases.

Term-pattern validation:

- Parse term `forms.default` as MF2.
- Require every `.match` selector to have a matching `.input`.
- Check selector names and keys against the locale profile.
- Warn on unreachable variants and missing catch-all rows.
- Derive required usage contexts from messages and verify the referenced term
  can produce them.

### Static Pattern Validation

Runs without concrete runtime values:

- unknown grammar function;
- invalid option name;
- invalid option value for locale profile;
- unresolved `with=$arg` reference;
- incompatible function/argument declarations where known.

### Bundle/Argument Validation

Runs with resource bundle and optional sample arguments:

- `:xg:term` receives term-like value;
- `:xg:agree with=$item` target has required morphology;
- required term forms and metadata fields are present;
- fallback policy is satisfied;
- referenced term/person IDs exist in bundle;
- locale profile exists.

Validators should produce structured diagnostics:

```json
{
  "code": "missing-term-form",
  "messageId": "inventory.pickup",
  "argument": "item",
  "termId": "item.sword",
  "feature": "forms.default",
  "severity": "error"
}
```

## Fallback Policy

Fallback is explicit and can be set at:

1. function call;
2. message resource;
3. locale profile;
4. application formatter config.

Policies:

| Policy | Behavior |
| --- | --- |
| `error` | throw/validation error if data is missing |
| `raw` | render raw term text without grammar |
| `neutral` | render locale-defined neutral phrase |
| `bestEffort` | use available heuristics and mark output as guessed |

`unknown` metadata is not the same as missing metadata. A person with
`gender=unknown` should usually choose neutral language rather than error.

## Editor Integration

An MF2 editor should expose grammar functions as structured nodes:

```text
Term(item)
  article: definite
  role: object
  fallback: error
```

It should show derived requirements:

```text
French requires: term form usage=definite
```

and link to term form rows plus optional metadata fields in the TMS.

## Conformance Contract

Every implementation must support:

- loading canonical JSON fixtures;
- resolving term/person refs;
- formatting all positive examples;
- reporting static requirements for each grammar function;
- negative diagnostics for missing required fields.

The conformance suite should be language-neutral and run in JS, Java, and Rust.
