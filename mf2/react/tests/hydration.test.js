import assert from "node:assert/strict";
import test from "node:test";
import React, { act } from "react";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { JSDOM } from "jsdom";
import { MF2, MF2Provider, useMF2 } from "@mojito-mf2/react";
import { parseMessage } from "@mojito-mf2/react/parser";

const h = React.createElement;

test("serialized parsed catalogs hydrate without changing server text and update through React", async () => {
  const version = "guide-v1";
  const bootstrap = JSON.parse(
    JSON.stringify({
      version,
      locale: "en",
      sourceLocale: "en",
      timeZone: "UTC",
      resources: {
        en: {
          guide: {
            message: parseMessage(
              "On {$date :date dateStyle=full}, read {#link}terms{/link}.",
              { allowedMarkup: ["link"] },
            ),
            label: parseMessage("Open terms"),
          },
        },
        fr: {
          guide: {
            message: parseMessage(
              "Le {$date :date dateStyle=full}, lire {#link}les conditions{/link}.",
              { allowedMarkup: ["link"] },
            ),
            label: parseMessage("Ouvrir les conditions"),
          },
        },
      },
    }),
  );
  function View() {
    const { format } = useMF2();
    return h(
      "p",
      { "data-catalog-version": bootstrap.version },
      h(MF2, {
        resource: "guide",
        name: "message",
        args: { date: "2026-01-02T01:04:05Z" },
        markupRenderers: {
          link: (children) =>
            h(
              "a",
              { href: "/terms", title: format("guide", "label") },
              children,
            ),
        },
      }),
    );
  }
  const view = (locale) => h(MF2Provider, { ...bootstrap, locale }, h(View));
  const serverMarkup = renderToString(view(bootstrap.locale));
  const dom = new JSDOM(`<div id="root">${serverMarkup}</div>`, {
    url: "https://example.com/",
  });
  const previous = new Map();
  for (const [name, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    previous.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value,
    });
  }
  let root;
  try {
    const container = document.querySelector("#root");
    const serverParagraph = container.firstChild;
    const serverLink = container.querySelector("a");
    const recoverableErrors = [];
    await act(async () => {
      root = hydrateRoot(container, view(bootstrap.locale), {
        onRecoverableError: (error) => recoverableErrors.push(error),
      });
    });
    assert.deepEqual(recoverableErrors, []);
    assert.equal(container.innerHTML, serverMarkup);
    assert.equal(container.firstChild, serverParagraph);
    assert.equal(container.querySelector("a"), serverLink);
    assert.equal(serverLink.title, "Open terms");
    await act(async () => root.render(view("fr")));
    assert.equal(container.firstChild, serverParagraph);
    assert.equal(container.querySelector("a"), serverLink);
    assert.equal(serverLink.title, "Ouvrir les conditions");
    assert.equal(serverLink.textContent, "les conditions");
    assert.match(container.textContent, /2 janvier 2026/);
    assert.equal(container.firstChild.dataset.catalogVersion, version);
  } finally {
    if (root) await act(async () => root.unmount());
    dom.window.close();
    for (const [name, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
});
