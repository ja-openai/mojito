# Language Feature Inventory

This inventory prevents the grammar agreement model from becoming
French-centric. It does not claim to solve every feature completely. It defines
where each feature fits in the model so locale adapters can support it without
changing the core resource bundle API.

## Core Design Rule

The core schema must allow unknown locale-defined features:

```json
{
  "morphology": {
    "gender": "feminine",
    "x-locale-feature": "locale-defined-value"
  }
}
```

Locale profiles define which features are meaningful and which functions use
them.

## Feature Matrix

| Feature | Example languages | Stored on | Used by |
| --- | --- | --- | --- |
| grammatical gender | French, Spanish, German, Russian, Arabic, Hebrew | term/person | article, adjective, verb, pronoun |
| common/neuter gender | Swedish, Danish, Dutch, Norwegian | term | article, adjective |
| noun class | Swahili, Zulu, Xhosa, Kinyarwanda, Luganda | term | agreement, prefixes, demonstratives |
| number | most languages; dual in Arabic/Slovenian/etc. | term/person/count | plural, verb, adjective, article |
| plural category | CLDR locales | count | selectors, count phrase |
| case | German, Russian, Polish, Finnish, Turkish, Greek | term/person | article, term form, pronoun |
| animacy | Russian, Polish, Czech, Ukrainian | term/person | case selection, agreement |
| definiteness | Arabic, Hebrew, Scandinavian languages, Balkan languages | term/message | article, construct/state |
| construct/state | Arabic, Hebrew, Persian-like constructions | term/message | term form, possession |
| person | Arabic, Hebrew, Romance verbs, pronouns | person | verb, pronoun |
| formality/honorific | Japanese, Korean, German, French, Spanish | person/context | pronoun, verb, phrase selection |
| politeness level | Japanese, Korean, Thai | context/person | verb, phrase selection |
| classifier/measure word | Chinese, Japanese, Korean, Thai | term/count | count phrase |
| countability | English, French, many languages | term | article, plural |
| mass noun behavior | English, French, German | term | article, count phrase |
| elision/vowel sound | French, Italian, English, Catalan | term phonology | article/contraction |
| h aspiré/silent h | French | term phonology | article/elision |
| initial mutation | Welsh, Irish, Scottish Gaelic | term phonology | article/preposition/possessive |
| sandhi/assimilation | Sanskrit-derived systems, Arabic article assimilation | term phonology | article/term rendering |
| clitic placement | Romance languages, Balkan languages | message/locale | verb/pronoun placement |
| word order | many languages | message pattern | translator-controlled pattern |
| adjective position | French, Spanish, Romance languages | message pattern | pattern plus agreement |
| possession | English, Semitic, Slavic, Turkic | term/person/message | possessive forms, construct |
| evidentiality | Turkish, Quechua, others | message/context | verb/phrase selection |
| tense/aspect/mood | most languages | verb/context | verb |
| inclusive/exclusive we | Austronesian, Dravidian, others | person/context | pronoun/verb |
| honorific noun/verb alternations | Japanese, Korean | term/person/context | phrase/verb selection |

## Model Placement

### Term Morphology

Use for facts intrinsic to a localized term:

```text
gender
nounClass
number
animacy
countability
phonology
declensionClass
classifier
forms
```

### Person Morphology

Use for speaker/listener/mentioned person facts:

```text
person
number
gender
formality
honorificLevel
pronounPreference
```

### Message Options

Use for grammatical role or construction chosen by the sentence:

```text
case=accusative
role=object
article=definite
definiteness=construct
politeness=formal
tense=past
```

### Locale Context

Use for app/session-level choices:

```text
speaker
listener
formality default
regional variant
script
orthography
```

## Required Extension Points

The core runtime must support:

1. locale-defined morphology fields;
2. locale-defined enum values;
3. locale-defined function options;
4. locale-defined compact flag profiles;
5. locale-defined validation rules;
6. locale-defined fallback strategies;
7. opaque form tables for rich inflection.

Without these, the system will collapse into a French/German-only feature set.

## Language Families And First-Class Tests

Minimum conformance families:

```text
French-like:
  gender + elision + article + adjective agreement

German-like:
  gender + case-sensitive articles

Slavic-like:
  case forms + animacy

Semitic-like:
  person/gender/number agreement + definiteness/construct path

Bantu-like:
  noun class agreement

Classifier-like:
  count + classifier selection

Formality-like:
  speaker/listener formality selection

Mutation-like:
  initial mutation triggered by article/preposition/possessive
```

Current fixtures cover the first five. Classifier, formality, and mutation
fixtures still need to be added.

## What The Core Should Not Do

The core should not hardcode:

- gender values;
- case lists;
- plural categories;
- noun class inventories;
- article tables;
- conjugation rules;
- mutation rules.

Those belong in locale profiles/adapters.

The core should only orchestrate:

```text
resolve resource -> validate requirements -> call locale adapter -> format
```
