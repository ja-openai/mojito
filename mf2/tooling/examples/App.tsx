import { MF2Provider } from "@mojito-mf2/react";
import { MF2, useMF2 } from "./messages.js";
import type { MF2Resources } from "@mojito-mf2/react";

function Cart({ count }: { count: number }) {
  const { format } = useMF2();
  return (
    <main>
      <MF2
        name="welcome"
        args={{ name: "Reader" }}
        markupRenderers={{
          terms: (children) => (
            <a href="https://example.com/terms">{children}</a>
          ),
        }}
      />
      <MF2 name="cart.summary" args={{ count }} />
      <button title={format("save")}>{format("save")}</button>
    </main>
  );
}

// resources comes from the static build or an application-owned loader. Swap
// locale and immutable resources together after a new catalog finishes loading.
export function App({
  resources,
  locale,
}: {
  resources: MF2Resources;
  locale: string;
}) {
  return (
    <MF2Provider
      resources={resources}
      locale={locale}
      sourceLocale="en"
      timeZone="UTC"
    >
      <Cart count={2} />
    </MF2Provider>
  );
}
