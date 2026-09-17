---
layout: doc
title:  "Supported File Formats"
categories: refs
permalink: /docs/refs/mojito-file-formats/
---


| Format                             | Source Resource File                   |
|:-----------------------------------|:---------------------------------------|
| Android Strings                    | res/values/strings.xml                 |
| CSV File                           | *.csv                                  |
| iOS/Mac Strings                    | Localizable.strings, InfoPList.strings |
| iOS/Mac Stringsdict                | Localizable.stringsdict                |
| Java Properties                    | *.properties                           |
| JS File                            | *.js                                   |
| JSON File                          | *.json                                 |
| Static MDX documents (explicit `-ft MDX`) | *.mdx                            |
| Chrome extension JSON &nbsp;&nbsp; | _locales/{locale}/messages.json                                 |
| RESW                               | *.resw                                 |
| RESX                               | *.resx                                 |
| PO File                            | *.pot                                  |
| TS File                            | *.ts                                   |
| XLIFF                              | *.xlf, *.xliff, *.sdlxliff, *.mxliff   |
| XTB File                           | *.xtb                                  |



### MDX documents

Use `mojito push -r Content -s ./content -ft MDX` to upload static MDX documents.
Mojito retains the source template and extracts headings, paragraphs, list items,
and blockquotes as separate strings. Normal Review Projects containing only MDX
open in Preview: browse or search the project's available pages and modules,
then select a passage to open its editor. Preview shows one document at a time
using saved translations. The List view remains available. Previewing does not
change review decisions.

Administrators can also use Mojito's **Content** page without creating a Review
Project. Enable **My Settings → Admin features → Show Content tab** to show its
navigation entry; direct Content links and APIs remain available to admins with
the tab hidden. Select a repository, branch, and language, browse the folder tree,
or search full asset paths. The catalogue uses **Previous** and **Next** with at
most 100 assets per page; folders load one level and 50 names at a time. **Back to
content** restores the catalogue filters and position.

In a target-language preview, choose **Edit translations** and click a passage to
open a compact editor without moving the page. Double-click is an optional shortcut
from reading mode. **More details** opens the full translation editor. Saving
refreshes the page's saved text; **Save & next** advances after the save succeeds.
Cmd/Ctrl+Enter saves, and Escape closes while retaining the draft. Reused modules
share their ordinary translation identity. The source-language preview is read-only.
This requires no repository tag or data-model change and does not change CLI access.

The server stores complete MDX source payloads in external blob storage. Preview
finds them by asset ID, branch ID, and the successful extraction's content hash;
no stored blob locator or additional schema is needed. Configure the `asset-content`
blob-storage route to Azure or S3; the default database route rejects MDX uploads.

To include another uploaded template, use a default relative import such as
`import Steps from './modules/steps.mdx'` and a standalone `<Steps />`. Push both
files to the same repository and branch. Preview expands up to three include
levels, blocks circular includes, and shows unavailable components in place.
Shared strings keep their own asset identity; include them in the review project
to edit them. Arbitrary React components, props, and children slots are not executed.

For a dropdown that previews alternative modules, use the reserved built-in
`PreviewChoice` wrapper:

```mdx
import Individual from './modules/Individual.mdx';
import Team from './modules/Team.mdx';

<PreviewChoice>
<Individual />
<Team />
</PreviewChoice>
```

Use paired standalone tags with two to eight self-closing, default-imported
relative MDX modules. Do not import `PreviewChoice`; attributes, direct prose,
expressions, and directly nested choice wrappers are unsupported. All alternatives
remain extracted and reviewable, with ordinary include depth and cycle limits.
Switching the dropdown changes only the visible preview, not review decisions.
This is a static review convention; a website compiler needs its own wrapper
adapter, as provided by the example below.

Add a `{/* mojito-id: stable.name */}` comment before a text block to preserve its
logical ID as its wording changes. Unannotated blocks use content-derived IDs.
Use `mojito pull -r Content -s ./content -t ./localized -ft MDX` to reconstruct
localized documents; the default target name is `name_<locale>.mdx`.
Importing existing localized MDX requires explicit IDs on every translatable block.
Link destinations and inline code stay unchanged while their surrounding prose translates.

This initial subset supports inline Markdown and standalone component tags without
attributes. Imports, component boundaries, and fenced code remain in the template.
Expressions, component attributes, inline JSX, raw HTML, frontmatter, tables,
reference links, and multiline list items are unsupported and report an extraction
error. Mojito resolves uploaded MDX templates for preview and does not run
JavaScript components. MDX uses the portable converter automatically and is
outside default file discovery.

The repository's `examples/mdx-content-site/` provides a runnable English/French
Vite + React website with nested and reused MDX modules, a static module-choice
dropdown, a push/import/pull helper, and an isolated Java CLI integration test.
The demo uses language-only locales `en` and `fr`. Its replaceable content adapter
assembles locale-specific module trees from the default localized filenames.
The build produces static HTML and browser assets
for a CDN, without a runtime Mojito dependency. It includes an MF2 catalog,
build-time resource bundles, and an interactive calendar example. Its README
includes an opt-in integrated Mojito UI demo and an optional translation worker
that imports only missing targets as review-needed candidates.

The reserved `PreviewMessage` adapter previews an MF2 catalog message with
explicit sample values:

```mdx
<PreviewMessage resource="./messages.mf2.json" name="calendar.summary" args='{"count":2}' />
```

Push the catalog as a separate asset on the same branch. Preview renders the
declared sample and opens the ordinary structured MF2 editor; a sample is not
coverage of every plural form or runtime state. General React component rendering,
arbitrary conditionals, and rich-markup adapters remain outside this static subset.

### Android Strings
Source Resource File (English): `res/values/strings.xml`


```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hello" description="Greeting from Main UI">Hello!</string>
</resources>
```

Localized Resource File (Spanish): `res/values-es/strings.xml`


```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hello" description="Greeting from Main UI">¡Hola!</string>
</resources>
```

By default, Mojito keeps translated `<a>` markup escaped so applications can read it as text and
pass it to an HTML parser. For applications that read the resource with `Resources.getText()` and
need Android to compile anchors into URL spans, generate the localized file with the Android filter
option `unescapeAnchorTags=auto`. Auto mode emits real anchors only for strings whose source XML
contains real `<a>` elements; source strings containing escaped `&lt;a&gt;` markup remain escaped. A
translation that does not preserve the source anchor tag exactly also remains escaped. Use
`unescapeAnchorTags=true` only to force real anchor output for every string in the asset. The option
must be enabled by the caller, for example with
`--filter-options unescapeAnchorTags=auto` on `pull`.

To enable auto mode server-wide without changing callers, set
`l10n.android-filter.auto-detect-anchor-tags=true`. The property defaults to `false`. An explicit
caller filter option (`unescapeAnchorTags=false`, `auto`, or `true`) overrides the server default.

### iOS/Mac Strings
Source Resource File (English): `en.lproj/Localizable.strings`


```c++
/* Greeting from Main UI */
"Hello!" = "Hello!";
```

Localized Resource File (Spanish): `es.lproj/Localizable.strings`


```c++
/* Greeting from Main UI */
"Hello!" = "¡Hola!";
```


### CSV

| Column Number | Description |
|:--------------|:------------|
| 1             | Source ID   |
| 2             | Source      |
| 3             | Target      |
| 4             | Comment     |
|||

Source Resource File (English): `example.csv`


```csv
hello,Hello!,Hello!,Greeting from Main UI
```

Localized Resource File (Spanish): `example_es-ES.csv`


```csv
hello,Hello!,¡Hola!,Greeting from Main UI
```


### iOS/Mac Stringsdict
Source Resource File (English): `en.lproj/Localizable.stringsdict`


```xml
<plist version="1.0">
<dict>
<key>Hello %d world(s)</key>
<dict>
    <!-- Greeting from Main UI -->
    <key>NSStringLocalizedFormatKey</key>
    <string>%#@world@</string>
    <key>world</key>
    <dict>
        <key>NSStringFormatSpecTypeKey</key>
        <string>NSStringPluralRuleType</string>
        <key>NSStringFormatValueTypeKey</key>
        <string>d</string>
        <key>one</key>
        <string>Hello %d world</string>
        <key>other</key>
        <string>Hello %d worlds</string>
    </dict>
</dict>
</dict>
</plist>
```

Localized Resource File (Spanish): `es.lproj/Localizable.stringsdict`


```xml
<plist version="1.0">
<dict>
<key>Hello %d world(s)</key>
<dict>
    <!-- Greeting from Main UI -->
    <key>NSStringLocalizedFormatKey</key>
    <string>%#@world@</string>
    <key>world</key>
    <dict>
        <key>NSStringFormatSpecTypeKey</key>
        <string>NSStringPluralRuleType</string>
        <key>NSStringFormatValueTypeKey</key>
        <string>d</string>
        <key>one</key>
        <string>Hola %d mundo</string>
        <key>other</key>
        <string>Hola %d mundos</string>
    </dict>
</dict>
</dict>
</plist>
```


### Java Properties

3 flavors are supported: `PROPERTIES` (`UTF-8`), `PROPERTIES_JAVA` (`ISO-8891`), `PROPERTIES_NOBASENAME` (`UTF-8` + `ISO-8859`)

Source Resource File (English): `en.properties` (no basename) or `messages.properties` (with basename)

```properties
# Greeting from Main UI
hello = Hello!
```

Localized Resource File (Spanish): `es.properties` (no basename) or `messages_es.properties` (with basename)


```properties
# Greeting from Main UI
hello = ¡Hola!
```


### JS
Source Resource File (English): `en.js`


```jsx
export default {
  // Greeting from Main UI
  "hello": "Hello!"
}
```

Localized Resource File (Spanish): `es.js`


```jsx
export default {
  // Greeting from Main UI
  "hello": "¡Hola!"
}
```


### JSON
Source Resource File (English): `example.json`


```jsx
{
  // Greeting from Main UI
  "hello": "Hello!"
}
```

Localized Resource File (Spanish): `example_es-ES.json`


```jsx
{
  // Greeting from Main UI
  "hello": "¡Hola!"
}
```

### Chrome extension JSON

See [chrome.i18n documentation](https://developer.chrome.com/extensions/i18n) documentation for more details

Source Resource File (English): `_locales/en/messages.json`
```json
{
  "hello": {
    "message": "Hello!",
    "description": "Greeting from Main UI"
  }
}
```

Localized Resource File (Spanish): `_locales/es-ES/messages.json`
```json
{
  "hello": {
    "message": "¡Hola!",
    "description": "Greeting from Main UI"
  }
}
```

### RESW
Source Resource File (English): `en/Resources.resw`


```xml
<?xml version="1.0" encoding="utf-8"?>
<root>
  <data name="hello" xml:space="preserve">
    <value>Hello!</value>
    <comment>Greeting from Main UI</comment>
  </data>
</root>
```

Localized Resource File (Spanish): `es/Resources.resw`


```xml
<?xml version="1.0" encoding="utf-8"?>
<root>
  <data name="hello" xml:space="preserve">
    <value>¡Hola!</value>
    <comment>Greeting from Main UI</comment>
  </data>
</root>
```


### RESX
Source Resource File (English): `Resources.resx`


```xml
<?xml version="1.0" encoding="utf-8"?>
<root>
  <data name="hello" xml:space="preserve">
    <value>Hello!</value>
    <comment>Greeting from Main UI</comment>
  </data>
</root>
```

Localized Resource File (Spanish): `Resources.es-ES.resx`


```xml
<?xml version="1.0" encoding="utf-8"?>
<root>
  <data name="hello" xml:space="preserve">
    <value>¡Hola!</value>
    <comment>Greeting from Main UI</comment>
  </data>
</root>
```


### TS
Source Resource File (English): `en.ts`


```jsx
namespace Translations {
  // Greeting from Main UI
  "hello": "Hello!"
}

export default Translations;
```

Localized Resource File (Spanish): `es.ts`


```jsx
namespace Translations {
  // Greeting from Main UI
  "hello": "¡Hola!"
}

export default Translations;
```


### XLIFF
Source Resource File (English): `resource.xliff`  


```xml
<?xml version="1.0" encoding="UTF-8"?>
<xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:okp="okapi-framework:xliff-extensions" version="1.2">
  <file original="" source-language="en" datatype="x-undefined">
    <body>
      <trans-unit id="1" resname="hello" datatype="php">
        <source>Hello!</source>
        <note>Greeting from Main UI</note>
      </trans-unit>
    </body>
  </file>
</xliff>  
```

Localized Resource File (Spanish): `resource_es-ES.xliff`


```xml
<?xml version="1.0" encoding="UTF-8"?>
<xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:okp="okapi-framework:xliff-extensions" version="1.2">
  <file original="" source-language="en" target-language="es-es" datatype="plaintext">
    <body>
      <trans-unit id="1" resname="hello" datatype="php">
        <source>Hello!</source>
        <target xml:lang="es-es">¡Hola!</target>
        <note>Greeting from Main UI</note>
      </trans-unit>
    </body>
  </file>
</xliff>
```


### PO File
Source Resource File (English): `messages.pot`   


```c
msgid ""
msgstr ""
"Project-Id-Version: PACKAGE VERSION\n"
"Report-Msgid-Bugs-To: \n"
"POT-Creation-Date: 2017-02-24 11:50-0800\n"
"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\n"
"Last-Translator: FULL NAME <EMAIL@ADDRESS>\n"
"Language-Team: LANGUAGE <LL@li.org>\n"
"Language: \n"
"MIME-Version: 1.0\n"
"Content-Type: text/plain; charset=utf-8\n"
"Content-Transfer-Encoding: 8bit\n"

#. Greeting from Main UI
#: file.js:2
msgctxt "hello"
msgid "Hello!"
msgstr ""
```

Localized Resource File (Spanish): `es_ES/LC_MESSAGES/messages.po`


```c
msgid ""
msgstr ""
"Project-Id-Version: PACKAGE VERSION\n"
"Report-Msgid-Bugs-To: \n"
"POT-Creation-Date: 2017-02-24 11:50-0800\n"
"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\n"
"Last-Translator: FULL NAME <EMAIL@ADDRESS>\n"
"Language-Team: LANGUAGE <LL@li.org>\n"
"Language: \n"
"MIME-Version: 1.0\n"
"Content-Type: text/plain; charset=utf-8\n"
"Content-Transfer-Encoding: 8bit\n"

#. Greeting from Main UI
#: file.js:2
msgctxt "hello"
msgid "Hello!"
msgstr "¡Hola!"
```


### XTB
Source Resource File (English): `Example-en-US.xtb`


```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE translationbundle>
<translationbundle lang="en-US">
    <translation id="1" key="hello" source="example.js">Hello!</translation>
</translationbundle>
```

Localized Resource File (Spanish): `Example-es-ES.xtb`


```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE translationbundle>
<translationbundle lang="en-US">
    <translation id="1" key="hello" source="example.js">¡Hola!</translation>
</translationbundle>
```
