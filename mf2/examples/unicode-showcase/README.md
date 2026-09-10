# Advanced Unicode MF2 showcase

A local Mojito dataset with **11 source messages**, **14 translations**, and
**163 runtime cases**. English is the source language; every message has a French
demo translation. Polish and Arabic have targeted plural/bidi examples. Targets
are imported as `REVIEW_NEEDED`.

Unicode does not rank examples by complexity. These examples cover the more
demanding features in its documentation, with provenance on each record:

| Message                          | What it demonstrates                                                       | Origin                                                                                                                                                  |
| -------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `unicode.01.party`               | 12 variants: gender × exact guest count                                    | [Unicode matchers](https://messageformat.unicode.org/docs/reference/matchers/#multiple-selectors)                                                       |
| `unicode.02.likes-shares`        | 9 variants: two independent plural selectors                               | [UTS #35 selector example](https://www.unicode.org/reports/tr35/tr35-messageFormat.html#selector)                                                       |
| `unicode.03.number-locals`       | Four locals, scientific notation, percent, padding, significant digits     | [Unicode number formatting](https://messageformat.unicode.org/docs/reference/functions/#number-formatting)                                              |
| `unicode.04.ordinal`             | English ordinal endings; different French categories                       | [Unicode number selection](https://messageformat.unicode.org/docs/reference/functions/#number-selection)                                                |
| `unicode.05.markup`              | Open/close and standalone markup                                           | [Unicode markup](https://messageformat.unicode.org/docs/reference/markup/)                                                                              |
| `unicode.06.grammatical-case`    | Custom case selector and person formatter                                  | [UTS #35 functions](https://www.unicode.org/reports/tr35/tr35-messageFormat.html#function)                                                              |
| `unicode.07.selector-precedence` | Specific matches after overlapping wildcard rows                           | [UTS #35 selection examples](https://www.unicode.org/reports/tr35/tr35-messageFormat.html#pattern-selection-examples)                                   |
| `unicode.08.locale-plurals`      | Unicode's Polish translation; Arabic's six plural forms                    | [Unicode locale plurals](https://messageformat.unicode.org/docs/reference/matchers/#number-selection-in-languages-with-more-than-two-plural-categories) |
| `unicode.09.date-styles`         | Four date/time styles                                                      | Adapted from [Unicode functions](https://messageformat.unicode.org/docs/reference/functions/), adding UTC                                               |
| `showcase.10.event-matrix`       | 36 variants: gender × status × exact count, plus date, currency and markup | Original composition using Unicode syntax                                                                                                               |
| `showcase.11.bidi`               | Arabic name inside LTR text; an LTR path inside Arabic                     | Original composition using [Unicode bidi isolation](https://www.unicode.org/reports/tr35/tr35-messageFormat.html#handling-bidirectional-text)           |

Published examples preserve their message content, with insignificant whitespace
normalized. French translations and the Arabic examples were authored for this
demo; the Polish translation is Unicode's example. The 36-variant matrix is **not
a published Unicode example**.

## Open in local Mojito

Run the importer below to create repository `unicode-mf2-showcase` with virtual
asset `unicode-showcase.mf2`. It prints the generated repository, asset, and text-unit
IDs and saves them to `/tmp/mojito-unicode-showcase-import.json`.

Open the local frontend at [localhost:5173](http://localhost:5173), select that
repository in Workbench, and choose a target locale. Use the printed text-unit ID
to open `/text-units/<tmTextUnitId>?locale=fr` directly. The party invitation,
likes-and-shares example, and 36-variant matrix have French translations; the
locale-plurals example also has Polish and Arabic translations.

For the matrix, try:

```json
{
  "hostGender": "female",
  "status": "ready",
  "guestCount": 2,
  "hostName": "Alex",
  "date": "2026-09-18T18:00:00Z",
  "price": 25,
  "url": "https://example.org/events/demo"
}
```

The Intl runtime produces:

> Alex : Elle est prête. Deux personnes sont inscrites. Début : 18 septembre 2026
> à 18:00 ; entrée : 25,00 €. Voir l’événement.

The link remains a structured markup part for an application renderer to handle.
The editor shows translatable patterns and protected placeholders; it is not a
fully configured application renderer.

## Reproduce and inspect limits

From the repository root, with frontend dependencies installed:

```sh
source webapp/use_local_npm.sh
node mf2/examples/unicode-showcase/validate.mjs
python3 mf2/examples/unicode-showcase/import-local.py
```

The importer targets only `localhost:8080`, sends the local development identity
`admin` through `x-forwarded-user`, and refuses to overwrite an existing repository
of the same name. It expects the local backend to use pre-authenticated headers;
set `MOJITO_LOCAL_USER` to your local admin username if it differs.
It creates a virtual `.mf2` asset, imports translations through the batch API as
drafts, and reads back the text, status, and `messageFormat: MF2` metadata.

Validation uses the actual editor's source/target contract checks and Mojito's JS
parser and formatter. Every English and French party, social, and event-matrix
branch has a case. `validation-results.json` records actual output, reference
expectations, warning counts, and portable-registry errors.

The September 9 validation asserts every recorded output and retains these boundaries:

- **Number formatting:** scientific/engineering/compact notation, the website's
  legacy `:number style=percent`, integer padding, and significant digits now
  match native Intl expectations. Both four-local example outputs are required
  assertions. Notation other than `standard`, significant-digit options, and
  legacy percent style are currently supported for formatting only: using an
  affected numeric value as a selector returns `bad-option`/`bad-selector` and
  chooses the fallback. Use standard notation and fraction-digit options for
  selection; use the standard `:percent` function for percent selection.
- **Digit limits:** integer and significant digits support 1–21; fraction digits
  support 0–100. Invalid sizes/combinations return `bad-option`. `:percent`,
  `:currency`, and `:integer` keep their fixed styles. Numeric locals inherit
  options, with direct overrides and MF2 numeric-type discard rules.
  These rules follow [Intl digit options](https://tc39.es/ecma402/#sec-setnumberformatdigitoptions)
  and [MF2 option inheritance and selection](https://unicode.org/reports/tr35/tr35-messageFormat.html#number-selection).
- **Preview registry:** the editor's portable registry lacks date/time/currency
  formatting. The dataset's date and event cases are validated with the explicit
  Intl registry, which supports these functions.
- **Custom grammar functions:** `ns:hasCase` and `ns:person` are host extensions,
  not built-in inflection. The fixture-only registry looks up supplied name forms;
  the regular registry's missing-function errors are also checked and recorded.
- **Warnings:** the complete 36-variant matrix has no overlap warnings, and Arabic
  can spell out fixed zero, singular, and dual quantities without omission warnings.
  Missing locale forms still receive reminders. The official Polish example
  deliberately includes both `other` and fallback; the editor flags `other` as a
  target-only form. `validation-results.json` records the current warning counts.

Unicode example material: Copyright © Unicode, Inc. See the included
[Unicode License V3](UNICODE-LICENSE.txt). Source URLs and retrieval dates are
stored in `dataset.json`.
