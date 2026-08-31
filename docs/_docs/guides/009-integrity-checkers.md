---
layout: doc
title:  "Integrity Checkers"
categories: guides
permalink: /docs/guides/integrity-checkers/
---

In this guide, let's go over the integrity checkers in {{ site.mojito_green }} in detail.  Integrity checkers perform checks on the translations against the source strings and reject the translations with errors.  This prevents translations with errors from being used in localized resource files which can lead to build faiilure or errors in application.


We use `mojito-cli` to configure integrity checkers in a repository.  Integrity checkers can be configured when you create and update repository in {{ site.mojito_green }} with `-it` parameter.  You can set integrity checker for each file extension of resource files.  For example, `-it resw:COMPOSITE_FORMAT,xlf:PRINTF_LIKE`.

```bash
    mojito repo-create -n MyRepo -it "properties:MESSAGE_FORMAT" -l de-DE es-ES
    
    mojito repo-update -n MyRepo -it "properties:MESSAGE_FORMAT" -l de-DE es-ES
```

### Available Integrity Checkers

| Integrity Checker                      | Recommended File Extensions &nbsp;&nbsp;&nbsp; | File Format                          |
|:---------------------------------------|:------------------------------- ---------------|:-------------------------------------|
| COMPOSITE_FORMAT                       | resw, resx                                     | RESW, RESX                           |
| MESSAGE_FORMAT                         | properties                                     | Java Properties                      |
| FORMATJS                               | json                                           | FormatJS ICU messages                |
| DOLLAR_TEMPLATE                        | properties, json                               | Python-style dollar templates        |
| FORMATJS_RICH_TEXT                     | json                                           | Legacy FormatJS apostrophe check      |
| PRINTF_LIKE                            | xml, strings,                                  | Android Strings, iOS/Mac Strings,    |
| SIMPLE_PRINTF_LIKE                     |                                                |                                      |
| WHITESPACE                             |                                                |                                      |
| TRAILING_WHITESPACE &nbsp;&nbsp;&nbsp; |                                                |                                      |


### Composite Format Integrity Checker

Composite format integrity checker validates that the placeholders of format `{some-identifier}` in the source string exist in the translation.

The translation gets rejected if any placeholder in the source string is missing in the translation.  There can be multiple placeholders in the source string.  The order of the placeholders can change in the translation. 

| Source String &nbsp;&nbsp;&nbsp; | Translation &nbsp;&nbsp;&nbsp; | Checker                                  |
|:---------------------------------|:-------------------------------|:-----------------------------------------|
| <small>Hello {0}!</small>        | <small>¡Hola {0}!</small>      | <small>OK</small>                        |
| <small>Hello {0}!</small>        | <small>¡Hola!</small>          | <small>FAIL missing placeholder</small>  |
| <small>{0.00}% used</small>      | <small>{0.00} used</small>     | <small>OK</small>                        |
| <small>{0.00}% used</small>      | <small>{0} used</small>        | <small>FAIL modified placeholder</small> |
| <small>{0} with {1}</small>      | <small>{1} con {0}</small>     | <small>OK</small>                        |



### Message Format Integrity Checker

Message format integrity checker validates message format in the translation against [icu4j Message Format](http://icu-project.org/apiref/icu4j/com/ibm/icu/text/MessageFormat.html).

The translation gets rejected if any placeholder in the source string is missing in the translation.  There can be multiple placeholders in the source string.  The order of the placeholders can change in the translation.

Missing curly braces or translating elements within the curly braces also cause the translation to be rejected.

| Source String                                                   | Translation                                                          | Checker           |
|:----------------------------------------------------------------|:---------------------------------------------------------------------|:----------------- |
| <small>{numFiles, plural, one{one file} other{# files}}</small> | <small>{numFiles, plural, one{un fichier} other{# fichiers}}</small> | <small>OK</small> |
| <small>{numFiles, plural, one{one file} other{# files}}</small> | <small>{numFiles, plural, one{un fichier} other{# fichiers}</small>  | <small>FAIL missing closing curly braces</small>  |
| <small>{numFiles, plural, one{one file} other{# files}}</small> | <small>{numFiles, plural, un{un fichier} autre{# fichiers}}</small>  | <small>FAIL translating quantity elements</small> |


### FormatJS Rich-Text Integrity Checker

FormatJS rich-text integrity checker rejects translations containing a single ASCII apostrophe
immediately before an opening rich-text tag. Configure it alongside any existing JSON integrity
checkers using the standard repository integrity-checker option:

```bash
    mojito repo-update -n MyFormatJsRepo -it "json:MESSAGE_FORMAT,json:HTML_TAG,json:FORMATJS_RICH_TEXT"
```

For example, `l'<privacyLink>...</privacyLink>` is rejected while
`l''<privacyLink>...</privacyLink>` is accepted. The checker does not modify translations or
generated files.

### Translation Integrity Checkers

`FORMATJS` and `DOLLAR_TEMPLATE` are parser-backed, prevention-only checkers. `FORMATJS`
validates message syntax, arguments and select branches, rich-text tags, boundary whitespace,
immutable email and URL literals, and the apostrophe-before-tag rule. `DOLLAR_TEMPLATE` validates
Python-style `$name` and `${name}` placeholders plus the grammar-neutral tag, whitespace, email,
and URL rules.

Both checkers report every deterministic target finding from the selected rule bundle. They never
rewrite a translation: a finding that has a deterministic repair in the neutral conformance corpus
is still rejected at Mojito's mutation boundary. Persisted source defects are reported by the
preflight command but do not reject a target save, because that operation cannot repair the source.

Each source and target is limited to 65,536 UTF-16 code units before parser-backed validation.
An oversized target fails the integrity check. An oversized source is a source defect and is
reported by preflight. Because the source cannot be parsed safely, a save against an oversized
persisted source does not receive the remaining source-to-target structural comparisons; resolve
that source defect before rollout.

Before enabling either checker, run a bounded read-only sample of active, used, non-rejected
current translations:

```bash
    mojito translation-integrity-preflight -n MyFormatJsRepo \
        --asset-extension json --checker-type FORMATJS --max-text-units 25
```

The command prints text-unit IDs, locales, asset paths, and diagnostic codes, but never source or
target text. It exits with status `2` when it finds target rejections or repairable target findings.
Source defects are advisory. An empty scope is an error rather than a successful preflight. One run
requests at most 101 exact-extension records and evaluates at most 100 records or 5,000,000 UTF-16
code units, whichever comes first. The code-unit threshold bounds evaluation after the REST
response is received; it is not a network-payload limit. A truncated result is a sample, not an
exhaustive or transactionally stable database scan. Narrow by locale or use a separate audited
database scan when complete inventory evidence is required.

After resolving the findings, enable the checker through the existing repository configuration:

```bash
    mojito repo-update -n MyFormatJsRepo -it "json:FORMATJS"
    mojito repo-update -n MyTemplateRepo -it "properties:DOLLAR_TEMPLATE"
```

`repo-update -it` replaces the repository's complete checker set. Include any unrelated existing
rows that must remain. Also replace overlapping legacy rows deliberately instead of configuring
both generations by accident; otherwise imports can receive duplicate comments and direct saves
can expose whichever checker fails first. `FORMATJS_RICH_TEXT` remains available unchanged for
repositories that are not ready for the broader `FORMATJS` contract.



### Printf-Like Integrity Checker

Printf-like integrity checker validates that the placeholders in the source string exist in the translation.  The placeholders are in the form of printf specifiers.

The translation gets rejected if any placeholder in the source string is missing in the translation or the specifier is modified.  There can be multiple placeholders in the source string.  The order of the placeholders can change in the translation.

| Source String                                          | Translation                                               | Checker           |
|:-------------------------------------------------------|:----------------------------------------------------------|:------------------|
| <small>Hello %@!</small>                               | <small>¡Hola %@!</small>                                  | <small>OK</small> |
| <small>%1$s of %2$s</small>                            | <small>%2$s의 %1$s</small>                                 | <small>OK</small> |
| <small>%1$d files and %2$d folders</small>             | <small>%1$d fichiers et dossiers</small>                  | <small>FAIL missing placeholder</small> |
| <small>%1$d files and %2$d folders</small>&nbsp;&nbsp; | <small>%1$d fichiers et %2$s dossiers</small>&nbsp;&nbsp; | <small>FAIL modified placeholder</small> |




### Simple Printf-Like Integrity Checker

Simple Printf-like integrity checker validates that the placeholders in the source string exist in the translation.  The placeholders are in the form of `%{number}`, for example, %1, %2, %3, etc.

The translation gets rejected if any placeholder in the source string is missing in the translation.  There can be multiple placeholders in the source string.  The order of the placeholders can change in the translation.

| Source String                                      | Translation                                        | Checker           |
|:---------------------------------------------------|:---------------------------------------------------|:------------------|
| <small>Hello %1!</small>                           | <small>¡Hola %1!</small>                           | <small>OK</small> |
| <small>%1 of %2</small>                            | <small>%2의 %1</small>                              | <small>OK</small> |
| <small>%1 files and %2 folders</small>             | <small>%1 fichiers et dossiers</small>             | <small>FAIL missing placeholder</small> |
| <small>%1 files and %2 folders</small>&nbsp;&nbsp; | <small>fichiers et %2 dossiers</small>&nbsp;&nbsp; | <small>FAIL missing placeholder</small> |




### Whitespace Integrity Checker

Whitespace integrity checker validates that the leading and trailing whitespaces in the source string exist in the translation.

The translation gets rejected if any leading or traingling whitespace in the source string is missing in the translation.

| Source String                                          | Translation                                                           | Checker           |
|:-------------------------------------------------------|:----------------------------------------------------------------------|:------------------|
| <small>[space]Hello %@![newline]</small>               | <small>[space]¡Hola %@![newline]</small>                              | <small>OK</small> |
| <small>[space]%1$d files and %2$d folders</small>      | <small>%1$d fichiers et %2$s dossiers</small>                         | <small>FAIL missing leading space</small>                    |
| <small>%1$d files and %2$d folders[newline]</small>    | <small>%1$d fichiers et %2$s dossiers</small>                         | <small>FAIL missing trailing newline</small>                 |
| <small>[space]%1 files and %2 folders[newline]</small> | <small>[newline]%1 fichiers et %2 dossiers[space]</small>&nbsp;&nbsp; | <small>FAIL modified leading and trailing whitepsace</small> |




### Trailing Whitespace Integrity Checker

Trailing whitespace integrity checker validates that the trailing whitespaces in the source string exist in the translation.

The translation gets rejected if any traingling whitespace in the source string is missing in the translation.

| Source String                                                      | Translation                                                           | Checker           |
|:-------------------------------------------------------------------|:----------------------------------------------------------------------|:------------------|
| <small>Hello %@![space][newline]</small>                           | <small>¡Hola %@![space][newline]</small>                              | <small>OK</small> |
| <small>%1$d files and %2$d folders[space]</small>                  | <small>%1$d fichiers et %2$s dossiers</small>                         | <small>FAIL missing trailng space</small>         |
| <small>%1$d files and %2$d folders[newline]</small>                | <small>%1$d fichiers et %2$s dossiers</small>                         | <small>FAIL missing trailing newline</small>      |
| <small>%1 files and %2 folders[space][newline]</small>&nbsp;&nbsp; | <small>%1 fichiers et %2 dossiers[newline][space]</small>&nbsp;&nbsp; | <small>FAIL modified trailing whitespaces</small> |




### Generated Android resource validation

Repository-configured text-unit checkers validate each translation before the
direct save endpoint writes it. Placeholder, Markdown-link, and plural-branch
contracts therefore fail at the translation boundary instead of waiting for a
localized asset pull.

Mojito can separately validate the final Android XML after the Android filter has
finished its output post-processing. Enable that check with
`l10n.android-filter.validate-generated-resources=true`. It defaults to `false` so
existing repositories can pass a canary pull before activation. The generated-
document check is owned by the Android output processor and rejects final resource
syntax that Mojito's Android parser cannot consume, including unescaped
apostrophes. Intentional empty output is not parsed.

The portable Android workflow already parses and serializes localized output
inside its format-owned pipeline. Neither output path repeats translation-fragment
placeholder, link, or plural comparisons after generation. Semantic translation
and accessibility findings still require qualified review and are not inferred
from syntax.

### Handling Rejected Translations


Interactive saves run configured integrity checkers before writing and return an error without
replacing the current translation. Offline, XLIFF, and localized-asset imports preserve their
existing bulk semantics: Mojito stores an invalid candidate as `TRANSLATION_NEEDED`, excludes it
from localized output, and adds an integrity-check error comment. The number of excluded
translations appears on the Repository page.

![Repository with Rejected Translation](./images/repository-rejected-translation.png)

Clicking on the number of rejected translations loads the Workbench with rejected translation.  You can correct the translation and change its status to `Needs Review` or `Accepted`.

![Workbench with Rejected Translation](./images/workbench-warning.png)
