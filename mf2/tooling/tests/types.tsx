import type { ReactNode } from "react";
import { MF2, useMF2, bind } from "../examples/messages.js";
import { createMF2 } from "@mojito-mf2/react";
import { createMessages } from "@mojito-mf2/react/bindings";
import { analyzeMessage, compileCatalog, generateTypes } from "../src/index.js";

const link = (children: ReactNode) => (
  <a href="https://example.com">{children}</a>
);
<MF2 name="cart.summary" args={{ count: 2 }} />;
<MF2 name="save" />;
<MF2
  name="welcome"
  args={{ name: "Reader" }}
  markupRenderers={{ terms: link }}
/>;
// @ts-expect-error misspelled message name
<MF2 name="cart.summmary" args={{ count: 2 }} />;
// @ts-expect-error missing argument object
<MF2 name="cart.summary" />;
// @ts-expect-error missing required count
<MF2 name="cart.summary" args={{}} />;
// @ts-expect-error incorrect numeric argument
<MF2 name="cart.summary" args={{ count: "two" }} />;
// @ts-expect-error excess argument
<MF2 name="cart.summary" args={{ count: 2, other: 3 }} />;
const extraArgs = { count: 2, other: 3 };
// @ts-expect-error exact checks also cover nonliteral objects
<MF2 name="cart.summary" args={extraArgs} />;
// @ts-expect-error missing required markup callback
<MF2 name="welcome" args={{ name: "Reader" }} />;
// @ts-expect-error wrong markup callback name
<MF2 name="welcome" args={{ name: "Reader" }} markupRenderers={{ link }} />;
<MF2
  name="welcome"
  args={{ name: "Reader" }}
  // @ts-expect-error a markup renderer must be a rendering function
  markupRenderers={{ terms: "link" }}
/>;
// @ts-expect-error callback cannot require a string; receives React children
// prettier-ignore
<MF2 name="welcome" args={{ name: "Reader" }} markupRenderers={{ terms: (child: string) => child }} />;
// @ts-expect-error extra binding
// prettier-ignore
<MF2 name="welcome" args={{ name: "Reader" }} markupRenderers={{ terms: link, other: link }} />;
// @ts-expect-error argumentless message cannot acquire arguments
<MF2 name="save" args={{ anything: 1 }} />;
// @ts-expect-error literal cannot override generated resource identity
<MF2 resource="other" name="save" />;

const formatters = useMF2();
formatters.format("save");
formatters.format("cart.summary", { count: 2 });
formatters.render("welcome", { name: "Reader" }, { terms: link });
// @ts-expect-error typo
formatters.format("missing");
// @ts-expect-error count is required
formatters.format("cart.summary");
// @ts-expect-error extra argument
formatters.format("cart.summary", extraArgs);
// @ts-expect-error wrong type
formatters.format("cart.summary", { count: "2" });
// @ts-expect-error markup is required for rich rendering
formatters.render("welcome", { name: "Reader" });
// @ts-expect-error extra callback
formatters.render("welcome", { name: "Reader" }, { terms: link, extra: link });
bind(createMF2({ resources: {}, locale: "en" })).format("save");

const unusual = createMessages<{
  "not.an.identifier": {
    args: { "user-name": string; __proto__: string };
    markupRenderers: {};
  };
}>("resource");
unusual.MF2({
  name: "not.an.identifier",
  args: { "user-name": "Reader", __proto__: "Text" },
});
// @ts-expect-error every unusual external key is required
unusual.MF2({ name: "not.an.identifier", args: { "user-name": "Reader" } });

analyzeMessage("{$n :number}", {
  functions: { custom: { input: ["number"] } },
});
compileCatalog({ label: "Hello" });
generateTypes({ label: "Hello" }, { resource: "messages" });

declare const dynamicName: "save" | "cart.summary";
// @ts-expect-error a union key must be narrowed before choosing its arguments
<MF2 name={dynamicName} />;
// @ts-expect-error dynamic key cannot omit potentially required parameters
formatters.format(dynamicName);
