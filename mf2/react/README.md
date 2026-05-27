# MF2 React Wrapper

Thin React layer over `@mojito-mf2/core`.

The package depends on `@mojito-mf2/core` through its public exports and treats
`react` and `react-dom` as peers. The checked-in React and Vite versions are
local demo/test tooling only, so consumers do not get an extra React runtime
through this wrapper.

The wrapper keeps the runtime in the JavaScript core:

- `MessageProvider` supplies catalog, locale, and default rich-text components.
  Per-message `components` props merge over those defaults, so app-level
  components and message-specific overrides can be composed.
  Nested `MessageProvider`s inherit parent catalog, locale, functions, and
  components unless a prop is explicitly overridden.
- `MessageProvider functions={registry}` passes the JavaScript core
  `FunctionRegistry` through all hooks and components, so React apps can wire
  app-specific formatters without forking the wrapper.
- `createCompiledMessageCatalog` accepts precompiled MF2 data-model objects.
  Import it and the runtime components from `@mojito-mf2/react/runtime` when an
  app should not bundle the source parser.
- `compileMessageCatalog` is the parser-enabled build-time helper that turns
  source strings into the compiled shape consumed by
  `createCompiledMessageCatalog`.
- `useMessage(id, values)` returns a formatted string.
- `useMessageParts(id, values)` returns structured parts.
- `MessageParts` exposes `{ entry, diagnostics, parts, locale }` through a
  render prop for custom previews, inspectors, and editor UI.
- `useMessageFormatter()` returns a FormatJS-style formatter object for
  callbacks and non-JSX code, with both `formatMessage` /
  `formatMessageToParts` and shorter `format` / `formatToParts` methods.
- `useMessageEntry(id)`, `useMessageDiagnostics(id)`, and `useMessageIds()`
  expose parsed catalog state for app/workbench validation UI.
- `messageContractFromModel`, `compareMessageContracts`,
  `MessageContractPanel`, and `SourceTargetDiagnostics` expose reusable
  source/target contract checks, advisory per-variant placeholder omission
  warnings, and safe insertion fixes for missing or shape-mismatched translator
  workbench UI tokens.
- `targetInsertionActionsFromContract` and `TargetInsertionPanel` expose
  source-constrained placeholder and markup insertion actions for target
  editors, with markup-kind-aware target present/missing state,
  missing-token-first visible buttons, and an overflow dropdown for larger
  contracts.
- `variantRowActionsFromModels`, `targetLocaleVariantRowActionsFromModels`,
  `targetSpecificVariantRowActionsFromModels`, and `VariantRowPanel` expose
  source-derived missing variant rows, target-locale CLDR row actions, and
  target-specific cleanup actions with plural samples and placeholder-preserving
  row skeletons for plural and multi-selector target editors, including a compact
  current-fallback preview before insertion. Action labels distinguish fixed
  selector values, CLDR plural categories, source context values, target-locale
  rows, and fallback rows. `insertVariantRowSource` inserts generated row
  snippets before the target fallback row when possible, and
  `removeVariantRowSource` removes target-specific rows from editable source.
  Multi-selector target-locale rows also expose bounded specific-row hint
  actions such as `male few` when the source has a matching context selector
  value.
- `sourceTargetScenarioRowsFromModels` and `SourceTargetScenarioMatrix` render
  source and target matcher scenarios side by side from shared CLDR sample
  values, making source-row gaps, target-specific rows, and target-locale CLDR
  plural rows visible
  without hand-editing values one at a time, with bounded specific-row hints
  for multi-selector messages. Target-specific rows that combine a source
  selector value with a target-locale plural category are classified as
  target-locale rows, while unsupported context selector values still remain
  target-specific rows. Status labels distinguish source rows missing from the
  target from target-locale CLDR rows that are missing or already present.
  `localePluralCoverageFromModels` and the rendered
  matrix also show row-kind badges plus per-selector target-locale category
  chips and selector-aware specific-row hints so translators can see fixed rows,
  CLDR rows, target-locale rows, fallback-covered categories, and categories
  whose currently sampled value is already covered by an exact numeric row.
  Scenario selector chips label fixed values, CLDR categories, fallback rows,
  and context values separately. Scenario sampling understands simple `:offset`
  locals with literal or variable-valued `add`/`subtract` options, so exact,
  plural-category, and fallback previews use base values that exercise the
  shifted selector without accidentally hitting fixed or CLDR-category offset
  rows.
  Variant row order is ignored for source/target coverage because a translator
  can reorder equivalent rows without changing the contract. Runtime ordering is
  still surfaced when it changes behavior: exact numeric, CLDR category, and
  fallback rows with the same selector tuple are row-order sensitive because the
  first matching row wins. Rows such as `* exact` and `exact *` are reported
  separately as selector-priority overlaps because the first differing selector,
  not the physical row order, decides the winner. The scenario matrix splits
  those notices so reviewers can tell row-order ties from selector-priority
  behavior.
- `FormattedMessage` renders `formatMessageToParts` output and delegates markup
  nodes such as `{#link}...{/link}` to React components, passing resolved markup
  options and attributes as props. It also accepts a function child when callers
  want to wrap or place the rendered chunks themselves.
- `FormattedMessage bidiIsolation="element"` renders expression parts as
  `<bdi dir="...">` nodes for HTML. String hooks still use Unicode isolates for
  copy-safe/plain-text output.
- `BidiIsolate` and `isolateText(value, direction)` expose the same isolation
  choices for host values that are rendered outside an MF2 message.
- `FormattedMessageBlock` wraps `FormattedMessage` in a block element with
  `dir="locale"`, `ltr`, `rtl`, or `auto`, so app chrome direction and message
  direction can stay separate.

The local Vite demo opens with a compact single-message translation editor:
source reference, one target textarea, source-constrained insertion helpers,
inline diagnostics, rendered preview, and a raw MF2 switch. The broader wrapper
examples and scenario tools stay available in a collapsed advanced playground.

Markup is semantic, not HTML. Translators can preserve or move a `{#link}`
span or a standalone `{#badge/}` node, but React decides what `link` or `badge`
means:

```jsx
const components = {
  link: ({ href, title, children }) => (
    <a href={href} title={title}>
      {children}
    </a>
  ),
};

const functions = FunctionRegistry.defaults().withFunction("upper", (call) =>
  call.value.toLocaleUpperCase(call.locale),
);
```

Basic shape:

```jsx
const catalog = createMessageCatalog({
  cart: `.input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}`,
});

<MessageProvider catalog={catalog} locale="en" components={components} functions={functions}>
  <FormattedMessage id="cart" values={{ count: 2 }} />
  <FormattedMessage id="cart" values={{ count: 2 }}>
    {(chunks) => <strong>{chunks}</strong>}
  </FormattedMessage>
  <FormattedMessageBlock id="cart" values={{ count: 2 }} dir="locale" />
  <MessageParts id="cart" values={{ count: 2 }}>
    {({ parts }) => <pre>{JSON.stringify(parts, null, 2)}</pre>}
  </MessageParts>
</MessageProvider>;
```

Parser-free runtime shape:

```jsx
import {
  FormattedMessage,
  MessageProvider,
  createCompiledMessageCatalog,
} from "@mojito-mf2/react/runtime";

const catalog = createCompiledMessageCatalog({
  greeting: {
    type: "message",
    declarations: [],
    pattern: ["Hello ", { type: "expression", arg: { type: "variable", name: "name" } }],
  },
});

<MessageProvider catalog={catalog} locale="en">
  <FormattedMessage id="greeting" values={{ name: "Mojito" }} />
</MessageProvider>;
```

Build-time compiler shape:

```js
import { writeFileSync } from "node:fs";
import { compileMessageCatalog } from "@mojito-mf2/react";

const compiled = compileMessageCatalog({
  cart: `.input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}`,
});

writeFileSync("mf2-catalog.json", JSON.stringify(compiled));
```

The package also has a tiny compiler demo:

```sh
npm run demo:compile
```

Run the demo:

```sh
npm install
npm run dev
```

Open `http://127.0.0.1:8790/`. The demo includes fixed examples and a small
playground with separate editable source and target MF2, JSON values, rendered
React output, escaped bidi-aware string output, structured `formatToParts` rows,
an editable local component-map expression, a render-direction switch that is
independent from the source/value editors, hook-backed
catalog/diagnostics/formatter status, source/target contract diagnostics with
safe insertion fixes and per-variant placeholder omission warnings, bidi
recipe rows for expression values, source-constrained target insertion buttons,
source-token present/missing state, missing source variant-row actions, rich
component prop inspection, and the corresponding wrapper code. Missing-row
buttons include CLDR sample hints,
preserve source placeholders in generated row skeletons, and preview current
fallback output before insertion. It also has a custom `:upper` function
registry
preset, showing how app-owned functions flow through `MessageProvider`,
`useMessage`, `useMessageParts`, `MessageParts`, and `FormattedMessage`. It also
shows the parser-enabled source catalog, the build-time compiled catalog shape,
parser-free precompiled runtime catalogs, the parsed message contract, and a
source/target scenario matrix generated from matcher variants and CLDR plural
samples, with selector key kind/sample details, source output, target output,
and missing-row/target-only/target-locale plural status so plural and
multi-selector behavior can be checked without hand-editing values one at a
time, with status summary chips and attention filters for narrowing review
cases, including literal and variable offset presets plus bounded specific-row
hints for target-locale plural categories inside multi-selector messages. The
code panel can switch between component, string-hook, and parts/rich-text
integration snippets, including both `useMessageParts` and the
FormatJS-style `formatMessageToParts` and `formatToParts` formatter methods.
The editable component map uses `React.createElement` so the prototype does not
need to ship a JSX compiler.

Run the wrapper smoke/build check:

```sh
npm run check
```

Run the lightweight server-render benchmark:

```sh
npm run bench:render
```
