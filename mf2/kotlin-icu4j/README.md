# MessageFormat2 Kotlin ICU4J Adapter

Opt-in ICU4J formatter registry for `messageformat2-kotlin`.

The Kotlin core package stays zero-runtime-dependency apart from Kotlin stdlib.
This adapter is a separate artifact for JVM applications that want ICU-backed
locale formatting:

```xml
<dependency>
  <groupId>com.box.l10n.mojito.mf2</groupId>
  <artifactId>messageformat2-kotlin-icu4j</artifactId>
  <version>0.1.0</version>
</dependency>
```

Use it explicitly through `Mf2Icu4jFunctions.registry()`:

```kotlin
val result = Mf2Formatter.formatMessage(
    model = message,
    arguments = arguments,
    locale = "fr-FR",
    functions = Mf2Icu4jFunctions.registry(),
)
```

The adapter supports ICU4J-backed formatting for `:number`, `:integer`,
`:percent`, `:currency`, `:date`, `:time`, `:datetime`, and `:relativeTime`.
It starts from the core portable registry, so string, offset, and plural
selection behavior remains available without adding fake date/currency/time
shims to portable behavior. Date/time formatting accepts `dateStyle`,
`timeStyle`, and `timeZone`, with legacy `length`, `precision`, `dateLength`,
`timePrecision`, and shared `style` aliases retained.

This artifact targets Kotlin/JVM and plain server JVM use. Android should use a
separate adapter decision because Android exposes `android.icu` package APIs
with API-level and packaging constraints that differ from ICU4J.

Run:

```sh
sh run.sh demo
sh run.sh check
```
