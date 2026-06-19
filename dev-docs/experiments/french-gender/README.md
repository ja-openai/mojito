# French Gender Classifier Exploration

This experiment tests a compact French noun-gender classifier built from:

1. suffix rules;
2. a masculine-exception Bloom filter;
3. a feminine-exception Bloom filter;
4. an exact correction map for build-time validation failures.

The prototype uses Lexique383 as the source lexicon. The script downloads or
reads `Lexique383.zip`, extracts unambiguous noun surface forms, trains suffix rules,
builds the two Bloom filters, then validates that every supported noun returns
the right gender.

## Current Results

See [results.md](results.md) for the generated measurements.

The initial run used Lexique383 noun surface forms with a single unambiguous masculine
or feminine gender. It excluded ambiguous homographs like `livre`, because a
single `genderOf(word)` API cannot return a correct single gender without sense
or domain context.

The prototype reached exact coverage for the supported words after the
correction map was added:

- `tiny-embed`: 2,000 common nouns, about 2 KB compact estimate.
- `web-medium`: 20,000 common nouns, about 15 KB compact estimate.
- `backend-large`: 42,295 unambiguous noun surface forms, about 25 KB compact estimate.

The compact estimate is not a Python object-size measurement. It estimates a
packed runtime representation: suffix bytes, Bloom bit arrays, and compact
word-plus-gender correction entries. A plain language runtime `HashMap` would
carry more overhead unless generated into arrays or a compact table.

## Runtime Contract

For the supported lexicon:

```text
genderOf(word) == knownGender
```

because the build validates every supported word and stores every remaining
mistake in the correction map.

For unknown words:

```text
genderOf(word) == best-effort guess
```

Bloom filters can still false-positive for arbitrary unknown input. This is
acceptable only if the API returns confidence/source metadata or if the caller
already restricts inputs to the supported dictionary.

Recommended API:

```ts
type GenderGuess = {
  gender: "masculine" | "feminine" | "unknown";
  exact: boolean;
  source: "correction" | "bloom-exception" | "suffix" | "unknown";
  confidence: number;
};
```

## Playground

Start a tiny local UI server:

```bash
python3 dev-docs/experiments/french-gender/french_gender_experiment.py \
  --zip /private/tmp/Lexique383.zip \
  --serve \
  --port 8765
```

Then open:

```text
http://127.0.0.1:8765
```

The API endpoint is:

```text
http://127.0.0.1:8765/api/guess?word=chien
```

## Why This Works For French

French noun gender is binary for ordinary nouns, and many derivational endings
carry useful gender signal:

- often feminine: `-tion`, `-sion`, `-ite`, `-ette`, `-ance`, `-ence`;
- often masculine: `-ment`, `-isme`, `-age`, `-oir`, `-eau`, `-ege`.

The suffix model handles regularities. The Bloom filters cheaply redirect known
suffix failures. The correction map patches deterministic failures caused by
Bloom false positives, ambiguous Bloom hits, or missing suffix coverage.

This solves only noun gender. French article rendering also needs:

- number: `le/la/l'` versus `les`;
- elision: `l'arbre`, `l'eau`;
- aspirated h: `le héros`, not `l'héros`;
- article type and preposition contraction: `du`, `au`, `des`, `aux`;
- adjective agreement when the generated phrase contains adjectives.

Those fields should be separate metadata, not inferred from gender alone.

## Language Fit

This technique works best when categories can be predicted from surface endings
with a manageable exception set:

- Strong fit: French, Spanish, Italian, Portuguese, Catalan.
- Good but larger category set: German, Greek, Russian, Ukrainian, Polish,
  Czech, Slovak. These need masculine/feminine/neuter and often plural or case
  class metadata, not just gender.
- Different shape: Swedish, Danish, Dutch, Norwegian. These often use
  common/neuter or dialectal gender choices.
- Harder fit: Arabic and Hebrew. Gender is useful, but number, person, construct
  state, and agreement behavior matter quickly.
- Noun-class systems: Swahili, Zulu, Xhosa, Kinyarwanda, Luganda. The same
  compiled-classifier idea can work, but the target category is noun class, not
  masculine/feminine gender, and there may be many classes.

For languages with three genders, replace the two Bloom filters with one filter
per exception class:

```text
masculineExceptionBloom
feminineExceptionBloom
neuterExceptionBloom
```

The runtime chooses the only positive filter, falls back to suffix rules if none
match, and returns unknown or a correction when multiple filters match.

## TMS/MF2 Direction

The compact classifier is useful for guessing and for bundled fallback support,
but the localization-system design should treat grammar metadata as locale data:

```json
{
  "item.iron_sword": {
    "fr": {
      "name": "épée de fer",
      "gender": "feminine",
      "startsWithVowelSound": true,
      "plural": "épées de fer"
    }
  }
}
```

That lets a developer define the corpus and lets localization data carry the
exact morphology. The classifier can prefill the fields and flag low-confidence
terms. MessageFormat 2 can then select or invoke locale-specific formatting
functions against that metadata instead of forcing the app to concatenate
English-shaped fragments.

Example conceptual MF2-style shape:

```text
You picked up {$item :article=definite}.
```

For French, a formatter could render:

```text
Vous avez ramassé l'épée de fer.
```

The advanced path is:

1. app developer supplies terms/entities;
2. TMS stores localized names and morphology;
3. built-in lexicons and suffix/Bloom models prefill metadata;
4. runtime message formatting uses explicit metadata first and guesses only as a
   fallback.

## Open Questions

- Whether the best compact exact representation is this Bloom/correction design
  or a minimal-perfect-hash exception table. The current numbers are small
  enough that both are plausible.
- Whether to include ambiguous homographs with domain-specific senses instead
  of excluding them.
- Whether article/elision/plural metadata should be compiled by the same script
  or handled as a separate French morphology pack.
- How much unknown-word quality matters versus exact behavior for a declared
  supported corpus.
