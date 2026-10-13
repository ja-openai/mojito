# React and MDX

`@mojito-mf2/react` renders parsed models from `@mojito-mf2/core`. It uses
that engine's formatter and Intl registry, with React as a peer dependency.
The package has no network client and makes no connection to Mojito.
React 18.3 and React 19 are supported.

## Authoring

Keep normal MDX prose in its existing document localization flow. Use explicit
MF2 catalog messages for parameters, plural/select branches, locale formatting,
and named rich text. Resource paths and catalog keys remain their existing
identities; no automatic IDs or JSX extraction are involved.

Use `<MF2>` inside an `MF2Provider` with resources prepared during the build:

```jsx
import { MF2, MF2Provider } from "@mojito-mf2/react";
import resources from "./parsed-resources.json";

const markupRenderers = {
  termsLink: (children) => <a href="/terms">{children}</a>,
  strong: (children) => <strong>{children}</strong>,
  br: (children) => {
    if (children !== null) throw new Error("br must be standalone");
    return <br />;
  },
};

function Greeting() {
  return (
    <MF2
      resource="messages.mf2.json"
      name="welcome"
      args={{ name: "Reader" }}
      markupRenderers={markupRenderers}
    />
  );
}

<MF2Provider resources={resources} locale="en" sourceLocale="en" timeZone="UTC">
  <Greeting />
</MF2Provider>;
```

The same component is usable in trusted application MDX through its component
map. Mojito's reserved `PreviewMessage` adapter retains its existing bounded
literal resource/name/sample mapping. Mojito never executes uploaded MDX or
application component code. The content-site example and Mojito preview share
`renderMF2Parts`; Mojito uses its own narrow inert markup renderers.

`markupRenderers` maps markup names to plain `(children) => ReactNode` callbacks,
typed as `MF2MarkupRenderers`. Paired tags receive their rendered children;
standalone tags receive `null`. These callbacks are invoked directly and must
not call React hooks. To use a React component, return it from a callback, such
as `termsLink: (children) => <Link>{children}</Link>`; do not pass `Link` itself.
The map must contain own enumerable callback properties.

Nesting is structurally validated before any callback runs. Application markup
renderers own semantic restrictions such as `br` being standalone or avoiding
nested interactive elements, and own elements, URLs, styles, and event handlers.
Catalog markup cannot supply those properties. React escapes strings. Arguments
accept strings, numbers, booleans, bigints, `null`, `undefined`, and `Date` values.
MF2 expressions produce strings; use markup renderers for rich content instead
of passing React elements, arrays, or objects as arguments.

## Prepare resources during the build

A resource catalog maps message names to parsed MF2 models. `resources`, typed
as `MF2Resources`, groups those catalogs by locale and resource path:
`locale -> resource path -> message name -> MF2 model`. Source catalog files
contain MF2 strings; the runtime receives their serializable parsed models.
For example, this build script creates the source resources used above:

```js
import { writeFile } from "node:fs/promises";
import { parseMessage } from "@mojito-mf2/react/parser";

const model = parseMessage(
  "Read {#termsLink}our {#strong}terms{/strong}{/termsLink}.{#br /}Welcome, {$name}",
  { allowedMarkup: ["termsLink", "strong", "br"] },
);
const resources = { en: { "messages.mf2.json": { welcome: model } } };
await writeFile("./parsed-resources.json", JSON.stringify(resources));
```

Parse each source and translated catalog during the build, adding translated
models under their locale keys. `parseMessage` validates syntax and markup in
every pattern. It rejects unknown markup names, crossed/unclosed markup, and markup
options/attributes. It does not generate code or exhaustively validate MF2
semantics, formatting inputs, or runtime behavior. Applications still own
source/translation contracts, formatting checks, and resource budgets.

Syntax failures throw `MF2ParseError`, exported from `/parser` and extending
`SyntaxError`. Its `message` includes diagnostic codes, source ranges, and
messages; its typed `diagnostics` property retains the parser diagnostics.
Markup validation failures throw ordinary errors. When adding file or message
context in a build tool, preserve the original error as the new error's `cause`.

The entrypoints keep authoring and build tools separate:

| Entrypoint                 | Exports and purpose                                                                                                                                                 |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@mojito-mf2/react`        | `MF2Provider`, `MF2`, `useMF2`, and `createMF2` for rendering catalog messages; `renderMF2Parts` for adapters that already have formatted parts. No parser imports. |
| `@mojito-mf2/react/parser` | `parseMessage` and `MF2ParseError` for build tools and development previews.                                                                                        |
| `@mojito-mf2/react/markup` | `validateMessageMarkup` for existing models and `validateMarkupParts` for formatted parts. Neither parses source strings.                                           |
| `@mojito-mf2/react/bindings` | `createMessages` for generated resource-specific React components and typed runtimes. No parser imports. |

## Formatting, fallback, and errors

`useMF2()` returns the provider's `MF2Runtime`. `createMF2(config)` exposes the same
`format(resource, name, args)` and
`render(resource, name, args, markupRenderers)` methods without a provider.
`format` returns plain text for attributes and imperative use, deliberately
flattening markup as the core string formatter does. `render` uses the parts API
and validated markup renderers. Raw source parsing is available only through
the explicit `/parser` entrypoint for build tools and development previews.

The selected locale is an exact key in `resources`. A missing message, formatting
error, or markup error tries the source catalog with **sourceLocale** for plural
selection and formatting. If all available versions have argument errors, the final
formatter recovery text is retained (for example `{$name}`). Missing/invalid
models without recoverable output produce `resource#name` as visible text.
`onError(error, { resource, name, locale })` observes each lookup or rendering
failure; throwing makes a build strict. It can run during React render, including
repeated development renders, so use an idempotent reporter without state writes.

`timeZone` defaults to `UTC`; explicit or inherited message timezone options take
precedence. `bidiIsolation` defaults to `default`. Rich output uses the core
`partsToString` isolation markers for expressions, including their direction;
string output follows the core formatter's numeric isolation optimizations.
No HTML parsing or `dangerouslySetInnerHTML` is involved.

### Application functions and formatter caches

The default Intl registry retains up to 64 formatter configurations per provider
or `createMF2` instance. Set `formatterCacheSize` to an integer from 0 to 1024;
zero disables caching. The cache retains Intl instances and normalized options,
not arguments, formatted strings, or catalogs.

To add trusted application functions, supply a registry built in application
code. The same registry is used for rich rendering, string formatting, and source
fallback:

```js
import { createMF2 } from "@mojito-mf2/react";
import { FunctionRegistry } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";

const functions = createIntlFunctionRegistry(FunctionRegistry, {
  timeZone: "UTC",
  formatterCacheSize: 32,
}).withFunction("app:upper", (call) => call.value.toUpperCase());
// Message source: {$name :app:upper}
const formatter = createMF2({ resources, locale: "en", functions });
```

`functions` is also a provider prop. A supplied registry owns its timezone,
functions, selectors, and caches; provider `timeZone` and `formatterCacheSize`
do not reconfigure it. Keep registry identity stable and replace the registry
when changing its configuration. Registries and markup callbacks are trusted
code, never downloaded as catalog content. Declare custom function signatures
separately in the catalog tooling so it can validate arguments and translations.
Pure callbacks support React's repeated rendering and server rendering.

Generated resource bindings from `@mojito-mf2/react/bindings` provide a typed
`useMF2()` hook. Its formatter and methods retain their identities while the
underlying provider configuration remains unchanged, so they can be used safely
as hook dependencies.

## Resources and loading

Replace the provider's immutable `resources` and `locale` together to switch
catalogs. The provider reacts to those props; it does not mutate the DOM's text
outside React. Fetching only obtains resources; your application owns caching,
loading, cancellation, errors, and the state swap. Do not mutate a catalog object
in place, or replace only locale while its resources are still loading.

The [content-site example](../../examples/mdx-content-site/README.md) demonstrates
both bundled catalogs and asynchronous, version-pinned locale files. It loads
before switching, ignores superseded requests, and retains the last successful
selection on failure. Pin the same catalog version, locale, source locale,
timezone, arguments, and Intl data/environment for server rendering and initial
hydration. Never resolve a mutable `latest` URL independently on the two sides.

The React runtime imports `@mojito-mf2/core/formatter` and
`@mojito-mf2/core/intl`, without importing the parser. The example build checks
its module graph and emits `mf2-build-info.json`; package tests check runtime
imports and the packed entrypoints. JSON message keys are **not automatically
tree-shaken**: explicitly split catalogs by locale/resource/route when needed.
This package does not supply an Intl polyfill or catalog publication system.

## Local checks

From the repository root with the Maven-managed Node runtime:

```sh
source webapp/use_local_npm.sh
npm --prefix mf2/react ci
npm --prefix mf2/react test
npm --prefix mf2/react run check:types
```

## Message-specific TypeScript bindings

`@mojito-mf2/tooling` checks flat MF2 source/translation catalogs, compiles models,
and generates bindings with exact message names, arguments and markup render
callbacks. See the [catalog tooling workflow](../tooling/README.md). Generated
bindings use `@mojito-mf2/react/bindings`, a small resource facade over this same
renderer. They contain no runtime catalog schema and import no parser or compiler.
The existing generic API remains available.
