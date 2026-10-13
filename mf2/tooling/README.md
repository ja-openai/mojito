# MF2 contracts and catalogs

`@mojito-mf2/tooling` checks explicitly authored MessageFormat 2 catalogs, compiles
existing core models and generates message-specific TypeScript bindings for
`@mojito-mf2/react`. It reuses the MF2 parser; it is not another formatting engine,
a JSX extractor, a network client, or a translation-quality evaluator.

## Build workflow

Install `@mojito-mf2/core` with this package and `@mojito-mf2/react` with React 18 or 19
in the consuming application. Type generation requires TypeScript 5.4 or newer.
A catalog is a flat `.mf2.json` object mapping stable message names to source text:

```json
{
  "cart.summary": ".input {$count :number}\n.match $count\none {{You have {$count} item.}}\n* {{You have {$count} items.}}",
  "welcome": "Welcome, {$name}. Read the {#terms}terms{/terms}."
}
```

```sh
mf2-catalog check en.mf2.json fr.mf2.json --locale fr
mf2-catalog compile en.mf2.json --out en.compiled.json
mf2-catalog compile fr.mf2.json --out fr.compiled.json
mf2-catalog types en.mf2.json --resource shop.mf2.json --out messages.ts
```

Check emits diagnostics and writes no files. All commands print JSON results.
Exit status is 0 for successful validation (possibly warnings), 1 for invalid
catalog/message contracts, and 2 for usage or IO failures. Compilation never
emits a partial successful catalog. Output is written atomically only after all
messages pass. Source paths, symlinks and hard links cannot be overwritten as
outputs. Generated output has deterministic ordering, no timestamp, and an
explicit artifact/contract version.

Compiled files contain `{ format, version, profile, messages }`. Place `messages`
under the existing runtime shape: `resources[locale][resource]`. Nothing changes
for applications already using precompiled core models directly. Validate an
artifact's format/version/profile when accepting it through your own loader.
A `model` profile artifact is not automatically suitable for the React runtime.

```tsx
import { MF2Provider } from "@mojito-mf2/react";
import { MF2, useMF2 } from "./messages.js";

function Cart({ count }: { count: number }) {
  const { format } = useMF2();
  return (
    <>
      <MF2 name="cart.summary" args={{ count }} />
      <MF2
        name="welcome"
        args={{ name: "Reader" }}
        markupRenderers={{
          terms: (children) => (
            <a href="https://example.com/terms">{children}</a>
          ),
        }}
      />
    </>
  );
}
```

Wrap the application in `<MF2Provider locale="fr" resources={resources}>`.
The same bindings work in trusted application MDX. `bind(createMF2(config))`
provides typed imperative `format(name, args)` and `render(name, args, markupRenderers)`.
Plain formatting deliberately flattens markup, so it does not need callbacks.
Generated bindings reject wrong message names, missing/wrong/extra arguments,
missing/wrong/extra callbacks and undeclared resources. Dynamic union message IDs
must be narrowed before formatting; otherwise required arguments could be hidden
by the union. Existing generic React calls remain available for genuinely dynamic
identities, with their existing runtime errors and fallback behavior.

The [complete example](examples/App.tsx) includes public source and translated
catalogs. `npm run example` checks them, compiles resources and regenerates its
binding. The end-to-end test renders French HTML with escaped interpolation and
a real application-owned link. Delivered email/static HTML contains no MF2 code.
An SPA loads the precompiled models plus formatter and rerenders for live values.
Locale loading, cancellation, resource splitting, SSR/hydration consistency and
atomic provider updates remain application responsibilities documented by the
[React package](../react/README.md).

## Reusable APIs

The main entrypoint is browser-safe: only the CLI imports Node IO. There are no
process-global catalog or analysis caches.

```js
import {
  analyzeMessage,
  analyzeModel,
  checkContracts,
  checkMarkupPatterns,
  checkTranslation,
  checkCatalog,
  compileCatalog,
  generateTypes,
} from "@mojito-mf2/tooling";

const source = analyzeMessage("Hello {$name}");
const target = analyzeMessage("Bonjour {$name}");
const checked = checkContracts(source.contract, target.contract);
// For a TMS editor with an existing parse result:
const sameContract = analyzeModel(parsedModel); // no source parsing, no mutation
```

`analyzeMessage(source, options)` and `analyzeModel(model, options)` return
`{ ok, diagnostics, model, contract }`. Consume model/contract only when `ok` is
true. `checkTranslation(source, target, options)` returns both analyses and
comparison diagnostics. `checkContracts` compares successful analyzer-produced
contracts; it is not a decoder for untrusted serialized contracts. TMS callers
can cache the source analysis for the currently edited unit and check new target
contracts without reparsing source or enumerating selector combinations.
`checkMarkupPatterns(sourcePattern, targetPattern, { profile: "model" })` checks
only rich markup in existing parsed patterns. TMS integrations can preserve their
own argument, alias and plural policies while sharing the same protected markup
rules. It ignores text and expressions, including application-specific functions,
and bounds/validates model data before traversal.

`checkCatalog(source, target, options)` checks every matched entry and separately
reports missing/extra keys, invalid source/target entries and contract failures.
`compileCatalog(catalog, options)` returns `{ ok, diagnostics, artifact, contracts }`;
artifact/contracts are null on any error. `generateTypes(catalog, { resource })`
returns `{ ok, diagnostics, code }`, with null code on any error. The generated
TypeScript imports a tiny `/bindings` runtime facade; it contains no schemas,
source messages, models, compiler, parser or tooling imports at runtime.

Diagnostics have stable codes, `error`/`warning` severity, a message, and optional
catalog key, source/target side, model location or parser character range.
Consumers should render the message and use the code for decisions. `ok` means
no errors; warnings are retained rather than silently discarded.

## Contracts and supported profiles

The default `react` profile targets the **current JavaScript Intl registry and
name-only React markup renderer**. It is a declared capability subset, not a
claim of cross-language runtime parity. The `model` profile uses the same known
function signatures, permits valid MF2 unmatched markup and markup metadata, and
allows application-declared custom function signatures:

```js
analyzeMessage("{$value :app:rating style=$style}", {
  profile: "model",
  functions: {
    "app:rating": {
      input: ["number"],
      options: { style: { types: ["string"], values: ["short", "long"] } },
    },
  },
});
```

The CLI accepts the corresponding object through `--functions signatures.json`.
A signature describes capabilities; it does not install formatter code. React
accepts explicitly declared custom signatures when the application supplies the
matching trusted `FunctionRegistry` to its runtime and preview. The compiler does
not install or verify that code. Unknown functions and options are errors.
Applications targeting other languages must verify their registry and conformance
coverage separately.

Static checks reject invalid literal numeric ranges, inverted effective precision
ranges, unsupported inherited selector options, missing/invalid currency and
invalid offsets. Numeric inheritance follows the current Intl adapter's reset
rules; a currency annotation may inherit its currency from a currency operand.
Unannotated local literal options can also be checked. Dynamic values still
require runtime validation. Capability signatures may intentionally override
built-in capabilities for an application registry.

External arguments include declaration operands, function/markup options,
selectors and every pattern branch. Local declarations are excluded. Unicode
binding, function, option and attribute names use NFC; signature keys follow the
same normalization and normalized duplicates are errors. Literal text and catalog
keys retain their exact identity. Maps preserve
valid names such as `__proto__`. Numeric functions infer `number`, string selectors
infer `string` (never a closed enum derived from branch labels), temporal functions
infer `Date | string`. Unannotated values retain the existing React scalar/Date
union. This intentionally does not generate permissive numeric-string types even
where the formatter can coerce them. A date string's contents still need runtime
validation. Arguments used only in some branches are conservatively required.

Translation checks protect input/local declarations, function uses and options
within each corresponding branch,
selector order/modes and exact-match branches. This strict first profile treats
local declaration literals as protected logic, not translatable prose. Plural
category branches can expand or contract by locale; exact numeric/string branches
cannot silently disappear. `targetLocale` checks target categories with the host
`Intl.PluralRules`; unused categories produce warnings. Omission of target locale
also produces a warning for plural selectors. The wildcard remains a valid
fallback; this checker does not require explicit branches for every category.

Rich markup checks preserve names, forms, multiplicity, protected options and
balanced hierarchy per branch, while allowing independent siblings to reorder.
Added plural branches inherit the most specific compatible source wildcard
contract. React additionally requires balanced nesting, bounded depth and no
markup options/attributes; destination URLs, events and props stay in callbacks.
Semantic restrictions such as nested interactive controls belong to the
application. MF2 model-profile unmatched markers retain their exact ordered
marker sequence instead of pretending to be HTML.

## Limits and performance

Default budgets are 100,000 UTF-16 source units/message, 128 declarations,
8 selectors, 256 variants, 10,000 parts, nesting depth 64, 10,000 catalog entries
and 10 million aggregate source/key units. Diagnostics are capped at 100, followed
by one explicit truncation error; truncated validation never succeeds. `options.limits` can supply positive
integer overrides. The CLI caps input files at 20 MB. Parsed model analysis also
bounds object depth, nodes and text; accessors, exotic objects, sparse arrays,
cycles and shared nodes are rejected. Already normalized models are reused, while
changed identifier paths are copied without mutating the caller. These are conservative resource controls, not a sandbox for arbitrary
JavaScript proxies or a guarantee against every hostile parser input.

Analysis parses once per message, has no unbounded global cache and never runs on
the rendering path. Exact branch lookups use a map. Added plural branches compare
only authored branches within the configured variant budget, with no Cartesian
selector expansion. Compiled artifacts contain models only; contracts stay in the
build/editor layer. Catalog JSON contents do not automatically tree shake—split
files by locale/resource/route. The `/bindings` import is tested with a loader that
fails if the parser enters the production module graph.

`npm run bench` reports median compile/check/typegen timings, cached-contract
comparison cost, source/AST raw and gzip sizes, and post-GC retained heap after
repeated catalog compilation. These synthetic measurements are reproducible
observations, not a FormatJS performance claim or proof that an application has
no leaks. Runtime performance still depends on the shared MF2 engine.

Static checks cannot prove linguistic correctness, all runtime values, custom
callback safety or every adapter option interaction. They complement formatter
errors, representative rendering tests, TMS review and cross-language fixtures.

## Local validation

```sh
source webapp/use_local_npm.sh
npm --prefix mf2/tooling ci
npm --prefix mf2/tooling run example
npm --prefix mf2/tooling test
npm --prefix mf2/tooling run check:types
npm --prefix mf2/tooling run bench
npm --prefix mf2/react test
npm --prefix mf2/react run check:types
```
