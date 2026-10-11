import React, { useEffect, useRef, useState } from "react";
import { MF2, MF2Provider, useMF2 } from "@mojito-mf2/react";
import { messageMarkupRenderers } from "./message-markup-renderers.mjs";

function Messages() {
  const { format, locale } = useMF2();
  return React.createElement(
    "div",
    {
      className: "loaded-messages",
      lang: locale,
      title: format("messages.mf2.json", "calendar.summary", { count: 2 }),
    },
    React.createElement(
      "p",
      null,
      React.createElement(MF2, {
        resource: "messages.mf2.json",
        name: "calendar.summary",
        args: { count: 2 },
      }),
    ),
    React.createElement(
      "p",
      null,
      React.createElement(MF2, {
        resource: "messages.mf2.json",
        name: "calendar.terms",
        markupRenderers: messageMarkupRenderers,
      }),
    ),
  );
}

// Resource fetching belongs to the application. A successful load replaces
// locale and catalogs in one state update; React then renders the new selection.
export function CatalogLoadingExample({ initialSelection, loadCatalog }) {
  const [selection, setSelection] = useState(initialSelection);
  const [requested, setRequested] = useState(initialSelection.locale);
  const [error, setError] = useState(false);
  const request = useRef(0);
  useEffect(
    () => () => {
      request.current += 1;
    },
    [],
  );

  async function selectLocale(event) {
    const locale = event.target.value;
    const ticket = ++request.current;
    setRequested(locale);
    setError(false);
    try {
      const next = await loadCatalog(locale);
      if (ticket !== request.current) return;
      if (
        next.locale !== locale ||
        next.version !== initialSelection.version ||
        !next.resources?.en ||
        !next.resources?.[locale]
      )
        throw new Error("Catalog does not match this page's pinned version");
      setSelection(next);
    } catch {
      if (ticket !== request.current) return;
      setRequested(selection.locale);
      setError(true);
    }
  }

  const french = initialSelection.locale === "fr";
  return React.createElement(
    "section",
    {
      className: "catalog-loading-example",
      "aria-busy": requested !== selection.locale,
    },
    React.createElement(
      "h2",
      null,
      french ? "Messages à la demande" : "Messages on demand",
    ),
    React.createElement(
      "label",
      null,
      french ? "Langue des messages " : "Message language ",
      React.createElement(
        "select",
        { value: requested, onChange: selectLocale },
        React.createElement("option", { value: "en" }, "English"),
        React.createElement("option", { value: "fr" }, "Français"),
      ),
    ),
    error
      ? React.createElement(
          "p",
          { role: "alert" },
          french
            ? "Chargement impossible. La langue précédente est conservée."
            : "Could not load messages. The previous language is still selected.",
        )
      : null,
    React.createElement(
      MF2Provider,
      {
        resources: selection.resources,
        locale: selection.locale,
        sourceLocale: "en",
        timeZone: "UTC",
      },
      React.createElement(Messages),
    ),
  );
}
