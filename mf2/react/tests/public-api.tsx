import type { ReactNode } from "react";
import {
  createMF2,
  MF2,
  MF2Provider,
  renderMF2Parts,
  useMF2,
} from "@mojito-mf2/react";
import type {
  MF2Arguments,
  MF2MarkupRenderers,
  MF2Config,
  MF2Runtime,
  MF2Resources,
} from "@mojito-mf2/react";
import { MF2ParseError, parseMessage } from "@mojito-mf2/react/parser";
import { FunctionRegistry } from "@mojito-mf2/core/formatter";
import {
  validateMarkupParts,
  validateMessageMarkup,
} from "@mojito-mf2/react/markup";

const message = parseMessage("Read {#link}terms{/link}", {
  allowedMarkup: ["link"],
});
const resources: MF2Resources = { en: { guide: { terms: message } } };
function TermsLink({ children }: { children: ReactNode }) {
  return <a href="https://example.com/terms">{children}</a>;
}
const markupRenderers: MF2MarkupRenderers = {
  link: (children) => <TermsLink>{children}</TermsLink>,
};
const config: MF2Config = {
  resources,
  locale: "fr",
  sourceLocale: "en",
  timeZone: "UTC",
  bidiIsolation: "default",
  formatterCacheSize: 32,
  onError(error, context) {
    const text: string = `${error.message}: ${context.resource}/${context.name}/${context.locale}`;
    void text;
  },
};
const customConfig: MF2Config = {
  ...config,
  functions: FunctionRegistry.portable().withFunction("app:upper", (call) =>
    call.value.toUpperCase(),
  ),
};
createMF2(customConfig);
const formatter: MF2Runtime = createMF2(config);
const args: MF2Arguments = {
  name: "reader",
  count: 2,
  date: new Date(),
  enabled: true,
  integer: 1n,
  absent: null,
  missing: undefined,
};
const label: string = formatter.format("guide", "terms", args);
const rich: ReactNode = formatter.render("guide", "terms", {}, markupRenderers);
validateMessageMarkup(message, ["link"]);
validateMarkupParts(
  [{ type: "markup", kind: "standalone", name: "break" }],
  ["break"],
);
const parts: ReactNode = renderMF2Parts(
  [{ type: "expression", value: "name", direction: "rtl" }],
  {},
  { bidiIsolation: "default" },
);

function Example() {
  const { format, locale } = useMF2();
  return (
    <button lang={locale} title={format("guide", "terms")}>
      <MF2
        resource="guide"
        name="terms"
        args={{ count: 2 }}
        markupRenderers={markupRenderers}
      />
    </button>
  );
}
const tree = (
  <MF2Provider {...config}>
    <Example />
  </MF2Provider>
);
void [label, rich, parts, tree];

function inspectParseError(error: unknown) {
  if (error instanceof MF2ParseError) {
    const syntaxError: SyntaxError = error;
    const diagnostic = error.diagnostics[0];
    const code: string = diagnostic.code;
    const message: string = diagnostic.message;
    const start: number = diagnostic.start;
    const end: number = diagnostic.end;
    void [syntaxError, code, message, start, end];
    // @ts-expect-error the diagnostic collection is read-only
    error.diagnostics.push(diagnostic);
  }
}
void inspectParseError;

// @ts-expect-error locale reflects configuration and cannot be changed in place
formatter.locale = "de";
const invalidMarkupRenderers: MF2MarkupRenderers = {
  // @ts-expect-error render callbacks receive children, not React component props
  link: TermsLink,
};
void invalidMarkupRenderers;

createMF2({
  locale: "en",
  // @ts-expect-error resources must be parsed models, not source strings
  resources: { en: { guide: { terms: "raw source" } } },
});
// @ts-expect-error markup bindings must be callbacks, not element names
formatter.render("guide", "terms", {}, { link: "a" });
// @ts-expect-error an identity includes both resource and message name
const missingIdentity = <MF2 name="terms" />;
// @ts-expect-error unsupported isolation options must not silently pass through
createMF2({ resources, locale: "en", bidiIsolation: "always" });
// @ts-expect-error the registry accepts trusted function implementations, not source code
createMF2({ resources, locale: "en", functions: "app:upper" });
// @ts-expect-error cache capacity must be numeric
createMF2({ resources, locale: "en", formatterCacheSize: "64" });
// @ts-expect-error expression arguments do not carry rich React content
formatter.format("guide", "terms", { name: <strong>Reader</strong> });
// @ts-expect-error expression arguments are scalar values or dates, not objects
formatter.render("guide", "terms", { name: { text: "Reader" } });
const callbackArgument = (
  // @ts-expect-error functions belong in markup bindings, not expression arguments
  <MF2 resource="guide" name="terms" args={{ name: () => "Reader" }} />
);
const arrayArgument = (
  // @ts-expect-error arrays are not scalar expression arguments
  <MF2 resource="guide" name="terms" args={{ name: ["Reader"] }} />
);
// @ts-expect-error symbols are not supported expression arguments
formatter.format("guide", "terms", { name: Symbol("Reader") });
void missingIdentity;
void [callbackArgument, arrayArgument];
