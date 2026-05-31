# MessageFormat2 Java ICU4J Adapter

Opt-in ICU4J formatter registry for `messageformat2-java`.

The core Java package stays zero-runtime-dependency. This adapter is a separate
artifact for applications that want ICU-backed locale formatting:

```xml
<dependency>
  <groupId>com.box.l10n.mojito.mf2</groupId>
  <artifactId>messageformat2-java-icu4j</artifactId>
  <version>0.1.0</version>
</dependency>
```

Use it explicitly through `Mf2Icu4jFunctions.registry()`:

```java
Mf2FormatOptions options = Mf2FormatOptions.builder()
    .locale("fr-FR")
    .functions(Mf2Icu4jFunctions.registry())
    .build();

Mf2FormatResult result = Mf2Formatter.formatMessage(message, arguments, options);
```

The adapter supports ICU4J-backed formatting for `:number`, `:integer`,
`:percent`, `:currency`, `:date`, `:time`, `:datetime`, and `:relativeTime`.
It starts from the core portable registry, so string, offset, and plural
selection behavior remains available without adding fake date/currency/time
shims to portable behavior. Date/time formatting accepts `dateStyle`,
`timeStyle`, and `timeZone`, with legacy `length`, `precision`, `dateLength`,
`timePrecision`, and shared `style` aliases retained.

Android should use a separate adapter decision. Android exposes `android.icu`
classes on API 24+, while the `RelativeDateTimeUnit` methods analogous to this
adapter are API 28+. Those package names and deployment constraints differ from
server-side ICU4J. This artifact targets plain JVM/server use; an Android adapter
should be a separate AAR or module using `android.icu` when the minSdk and size
tradeoffs are acceptable.

Run:

```sh
sh run.sh demo
sh run.sh check
```
