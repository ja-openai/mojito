---
layout: doc
title:  "Managing Locales"
categories: guides
permalink: /docs/guides/managing-locales/
---

In this guide, let's go over locales in {{ site.mojito_green }} in detail.  Every repository is configured with set of locales.  These locales are used to create translation requests as well as to generate localized files.


### Edit target locales in Settings

Administrators can open **System Settings → Repository settings**, choose a repository, and edit
**Translation locales** with the searchable locale picker. Select **Save changes** to apply the
selection, or **Reset draft** to discard edits. An empty selection removes all target locales.

The source locale stays unchanged. Existing targets keep their inheritance and partial-translation
settings; newly added targets inherit from the source and require full translation. A parent locale
cannot be removed while a selected child still inherits from it. Use the CLI for inheritance and
partial-translation configuration.

Removing a target locale does not delete its translations or generated files. Generation exclusions
remain separate: see [withholding a locale from generation]({{ site.url }}/docs/guides/generating-localized-files/#withholding-a-locale-from-generation).

### Configure locales with the CLI

Locales can also be configured when you create and update a repository with the `-l` parameter.

    mojito repo-create -n MyRepo -l de-DE es-ES fr-FR ja-JP

    mojito repo-update -n MyRepo -l de-DE es-ES fr-FR ja-JP


{{ site.mojito_green }} requires that the locale name follows BCP 47 syntax with region subtag, in the form of language-region.  See [Locales]({{ site.url }}/docs/refs/mojito-locales/) for the complete list of available locales in {{ site.mojito_green }}.



### Partially Translated Locales

By default, all locales configured in the repository are required to be fully translated.  These locales get automatically included in the translation requests.

You can configure the locales to be partially translated.  English in United Kingdom (en-GB) is a good example because most of the strings do not need to be "translated".  Source strings can be used as-is in most cases and only some strings that are specific to English in United Kingdom need to be overriden.

```bash
mojito repo-update -n MyRepo -l "(en-GB)" es-ES fr-FR ja-JP zh-CN zh-TW
```

The above example adds English in United Kingdome (en-GB) to `MyRepo` repository.  Having parenthesis around the locale excludes the locale from being fully translated.



### Locale Inheritance

If the language is the same but the region is different, majority of the translations can be shared.  For example, French in Switzerland (fr-CH) can share translations of French in France (fr-FR).  In such case, you can set up locale inheritance so that translations of parent locale can be re-used as translations of child locale.  The translation requests only include parent locales and the children locales are also considered as partially translated locales.

```bash
mojito repo-update -n MyRepo -l "(fr-CH)->fr-FR" fr-FR ja-JP zh-CN zh-TW
```

The above example makes French in Switzerland (fr-CH) the child locale of French in France (fr-FR).


Note that the partially translated locales are displayed in grey color in locales list.


![Partially Translated Locales](./images/partially-translated-locales.png)

### Update multiple repositories at once

When adding support for a locale, it is likely that multiple repositories must be updated at the same time. The `repo-update` provides the `-rns` option to do so.

```bash
mojito repo-update -rns android ios -l "(en-GB)" es-ES fr-FR ja-JP zh-CN zh-TW
```
