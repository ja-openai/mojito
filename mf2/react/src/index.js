import React, { createContext, useContext, useMemo } from "react";
import {
  FunctionRegistry,
  formatMessage,
  formatMessageToParts,
  partsToString,
} from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
import { validateMarkupParts } from "./markup.js";

export function renderMF2Parts(
  parts,
  markupRenderers = {},
  { bidiIsolation = "default" } = {},
) {
  const names = Object.keys(markupRenderers).filter(
    (name) => typeof markupRenderers[name] === "function",
  );
  validateMarkupParts(parts, names);
  const stack = [{ children: [] }];
  for (const [index, part] of parts.entries()) {
    if (part.type !== "markup") {
      stack.at(-1).children.push(partsToString([part], bidiIsolation));
    } else if (part.kind === "open") {
      stack.push({ name: part.name, key: index, children: [] });
    } else {
      const node =
        part.kind === "close"
          ? stack.pop()
          : { name: part.name, key: index, children: null };
      stack
        .at(-1)
        .children.push(
          React.createElement(
            React.Fragment,
            { key: node.key },
            markupRenderers[node.name](node.children),
          ),
        );
    }
  }
  return stack[0].children;
}

function own(object, key) {
  return object && Object.hasOwn(object, key) ? object[key] : undefined;
}

function checkArguments(args) {
  if (!args || typeof args !== "object" || Array.isArray(args))
    throw new Error("MF2 arguments must be an object");
  for (const value of Object.values(args)) {
    if (
      (value !== null &&
        typeof value === "object" &&
        !(value instanceof Date)) ||
      typeof value === "function" ||
      typeof value === "symbol"
    )
      throw new Error(
        "MF2 arguments must be scalars or Date values; use markup bindings for rich content",
      );
  }
}

export function createMF2({
  resources,
  locale,
  sourceLocale = "en",
  timeZone = "UTC",
  bidiIsolation = "default",
  onError,
}) {
  // Fail configuration errors early; never depend on the server/browser default zone.
  new Intl.DateTimeFormat(locale, { timeZone });
  new Intl.DateTimeFormat(sourceLocale, { timeZone });
  const functions = createIntlFunctionRegistry(FunctionRegistry, { timeZone });
  const report = (error, resource, name, language) =>
    onError?.(error instanceof Error ? error : new Error(String(error)), {
      resource,
      name,
      locale: language,
    });
  function resolve(resource, name, args, markupRenderers, rich) {
    const languages =
      locale === sourceLocale ? [locale] : [locale, sourceLocale];
    let recovered;
    for (const language of languages) {
      const model = own(own(own(resources, language), resource), name);
      if (!model) {
        report(
          new Error(`Unknown MF2 message: ${language}/${resource}#${name}`),
          resource,
          name,
          language,
        );
        continue;
      }
      let result;
      let value;
      try {
        checkArguments(args);
        const options = { locale: language, functions, bidiIsolation };
        if (rich) {
          result = formatMessageToParts(model, args, options);
          value = renderMF2Parts(result.parts, markupRenderers, {
            bidiIsolation,
          });
        } else {
          // Attributes intentionally flatten markup; rich rendering uses bindings.
          result = formatMessage(model, args, options);
          value = result.value;
        }
      } catch (error) {
        report(error, resource, name, language);
        continue;
      }
      for (const error of result.errors)
        report(error, resource, name, language);
      if (!result.hasErrors) return value;
      recovered = value;
    }
    // Keep formatter recovery text (e.g. {$name}) when no clean fallback exists.
    return recovered ?? `${resource}#${name}`;
  }
  return {
    locale,
    format: (resource, name, args = {}) =>
      resolve(resource, name, args, undefined, false),
    render: (resource, name, args = {}, markupRenderers = {}) =>
      resolve(resource, name, args, markupRenderers, true),
  };
}

const MF2Context = createContext(null);

export function MF2Provider({
  children,
  resources,
  locale,
  sourceLocale = "en",
  timeZone = "UTC",
  bidiIsolation = "default",
  onError,
}) {
  const value = useMemo(
    () =>
      createMF2({
        resources,
        locale,
        sourceLocale,
        timeZone,
        bidiIsolation,
        onError,
      }),
    [resources, locale, sourceLocale, timeZone, bidiIsolation, onError],
  );
  return React.createElement(MF2Context.Provider, { value }, children);
}

export function useMF2() {
  const value = useContext(MF2Context);
  if (!value) throw new Error("useMF2 requires an MF2Provider");
  return value;
}

export function MF2({ resource, name, args, markupRenderers }) {
  return useMF2().render(resource, name, args, markupRenderers);
}
