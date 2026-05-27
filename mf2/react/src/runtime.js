import React, { createContext, useContext, useMemo } from "react";

import {
  FunctionRegistry,
  formatMessage as formatCoreMessage,
  formatMessageToParts as formatCoreMessageToParts,
} from "@mojito-mf2/core/runtime";

export { FunctionRegistry };

const MessageContext = createContext(null);
const RTL_LANGUAGES = new Set(["ar", "dv", "fa", "he", "iw", "ks", "ku", "ps", "sd", "ug", "ur", "yi"]);
const RTL_SCRIPTS = new Set(["Adlm", "Arab", "Hebr", "Mand", "Nkoo", "Rohg", "Samr", "Syrc", "Thaa"]);

export function createCompiledMessageCatalog(compiledEntries) {
  const entries = new Map();
  for (const [id, entry] of Object.entries(compiledEntries ?? {})) {
    entries.set(id, normalizeCompiledEntry(id, entry));
  }
  return {
    get(id) {
      return entries.get(id) ?? null;
    },
    ids() {
      return [...entries.keys()];
    },
  };
}

function normalizeCompiledEntry(id, entry) {
  if (typeof entry === "string") {
    throw new TypeError("Source strings require createMessageCatalog from the parser-enabled React entry point.");
  }
  if (entry == null || typeof entry !== "object") {
    throw new TypeError(`Compiled MF2 catalog entry ${id} must be a model or { model, diagnostics, source } object.`);
  }
  const hasModel = Object.prototype.hasOwnProperty.call(entry, "model");
  const model = hasModel ? entry.model : entry;
  return {
    id,
    source: hasModel ? entry.source : undefined,
    model,
    diagnostics: Array.isArray(entry.diagnostics) ? entry.diagnostics : [],
  };
}

export function MessageProvider({ catalog, locale, components, functions, children }) {
  const parent = useContext(MessageContext);
  const value = useMemo(
    () => ({
      catalog: catalog ?? parent?.catalog,
      locale: locale ?? parent?.locale ?? "en",
      components: mergeComponents(parent?.components, components),
      functions: functions ?? parent?.functions,
    }),
    [catalog, components, functions, locale, parent],
  );
  return React.createElement(MessageContext.Provider, { value }, children);
}

export function useMessage(id, values = {}, options = {}) {
  const context = useMessageContext();
  return useMemo(() => {
    const entry = requireMessage(context.catalog, id);
    if (entry.diagnostics.length > 0) return `{${id}}`;
    return formatCoreMessage(entry.model, values, {
      locale: options.locale ?? context.locale,
      bidiIsolation: options.bidiIsolation ?? "none",
      functions: options.functions ?? context.functions,
    });
  }, [context, id, values, options.locale, options.bidiIsolation, options.functions]);
}

export function useMessageEntry(id) {
  const context = useMessageContext();
  return useMemo(() => requireMessage(context.catalog, id), [context.catalog, id]);
}

export function useMessageDiagnostics(id) {
  const entry = useMessageEntry(id);
  return entry.diagnostics;
}

export function useMessageIds() {
  const context = useMessageContext();
  return useMemo(() => context.catalog?.ids?.() ?? [], [context.catalog]);
}

export function useMessageParts(id, values = {}, options = {}) {
  const context = useMessageContext();
  return useMemo(() => {
    const entry = requireMessage(context.catalog, id);
    if (entry.diagnostics.length > 0) return [{ type: "fallback", source: id }];
    return formatCoreMessageToParts(entry.model, values, {
      locale: options.locale ?? context.locale,
      functions: options.functions ?? context.functions,
    });
  }, [context, id, values, options.locale, options.functions]);
}

export function useMessageFormatter(defaultOptions = {}) {
  const context = useMessageContext();
  const defaultLocale = defaultOptions.locale;
  const defaultBidiIsolation = defaultOptions.bidiIsolation;
  const defaultFunctions = defaultOptions.functions;
  return useMemo(
    () => {
      const formatMessage = (id, values = {}, options = {}) => {
        const entry = requireMessage(context.catalog, id);
        if (entry.diagnostics.length > 0) return `{${id}}`;
        return formatCoreMessage(entry.model, values, {
          locale: options.locale ?? defaultLocale ?? context.locale,
          bidiIsolation: options.bidiIsolation ?? defaultBidiIsolation ?? "none",
          functions: options.functions ?? defaultFunctions ?? context.functions,
        });
      };
      const formatMessageToParts = (id, values = {}, options = {}) => {
        const entry = requireMessage(context.catalog, id);
        if (entry.diagnostics.length > 0) return [{ type: "fallback", source: id }];
        return formatCoreMessageToParts(entry.model, values, {
          locale: options.locale ?? defaultLocale ?? context.locale,
          functions: options.functions ?? defaultFunctions ?? context.functions,
        });
      };
      return {
        entry(id) {
          return requireMessage(context.catalog, id);
        },
        diagnostics(id) {
          return requireMessage(context.catalog, id).diagnostics;
        },
        format: formatMessage,
        formatMessage,
        formatMessageToParts,
        formatToParts: formatMessageToParts,
        ids() {
          return context.catalog?.ids?.() ?? [];
        },
      };
    },
    [context.catalog, context.locale, context.functions, defaultLocale, defaultBidiIsolation, defaultFunctions],
  );
}

export function MessageParts({ id, values = {}, locale, functions, children }) {
  const context = useMessageContext();
  const entry = requireMessage(context.catalog, id);
  const resolvedLocale = locale ?? context.locale;
  const resolvedFunctions = functions ?? context.functions;
  const diagnostics = entry.diagnostics;
  const parts = useMemo(() => {
    if (diagnostics.length > 0) return [{ type: "fallback", source: id }];
    return formatCoreMessageToParts(entry.model, values, {
      locale: resolvedLocale,
      functions: resolvedFunctions,
    });
  }, [diagnostics, entry.model, id, resolvedLocale, resolvedFunctions, values]);
  if (typeof children !== "function") return null;
  return children({
    id,
    entry,
    diagnostics,
    parts,
    locale: resolvedLocale,
  });
}

export function FormattedMessage({ id, values = {}, components, locale, functions, bidiIsolation = "none", children }) {
  const context = useMessageContext();
  const entry = requireMessage(context.catalog, id);
  const resolvedLocale = locale ?? context.locale;
  if (entry.diagnostics.length > 0) {
    return React.createElement("span", { "data-mf2-error": entry.diagnostics[0]?.code }, `{${id}}`);
  }
  const parts = formatCoreMessageToParts(entry.model, values, {
    locale: resolvedLocale,
    functions: functions ?? context.functions,
  });
  const rendered = renderParts(parts, {
    values,
    components: mergeComponents(context.components, components),
    bidiIsolation,
  });
  if (typeof children === "function") {
    return children(rendered, { id, entry, parts, locale: resolvedLocale });
  }
  return rendered;
}

export function FormattedMessageBlock({
  as = "div",
  id,
  values = {},
  components,
  locale,
  functions,
  dir = "locale",
  bidiIsolation = "element",
  ...props
}) {
  const context = useMessageContext();
  const resolvedLocale = locale ?? context.locale;
  return React.createElement(
    as,
    { ...props, dir: resolveDirection(dir, resolvedLocale) },
    React.createElement(FormattedMessage, {
      id,
      values,
      components,
      locale: resolvedLocale,
      functions,
      bidiIsolation,
    }),
  );
}

export function renderParts(parts, { values = {}, components = {}, bidiIsolation = "none" } = {}) {
  const root = [];
  const stack = [{ children: root }];
  for (const part of parts) {
    if (part.type === "text") {
      current(stack).children.push(part.value ?? "");
    } else if (part.type === "fallback") {
      current(stack).children.push(`{${part.source ?? ""}}`);
    } else if (part.type === "expression") {
      current(stack).children.push(renderExpression(part.value ?? "", bidiIsolation, part.direction));
    } else if (part.type === "markup" && part.kind === "open") {
      stack.push({ part, children: [] });
    } else if (part.type === "markup" && part.kind === "close") {
      closeMarkup(stack, components, values);
    } else if (part.type === "markup" && part.kind === "standalone") {
      current(stack).children.push(renderMarkup(part, [], components, values));
    }
  }
  while (stack.length > 1) closeMarkup(stack, components, values);
  return React.createElement(
    React.Fragment,
    null,
    root.map((node, index) => React.createElement(React.Fragment, { key: index }, node)),
  );
}

function mergeComponents(providerComponents, messageComponents) {
  if (messageComponents == null) return providerComponents ?? {};
  return { ...(providerComponents ?? {}), ...messageComponents };
}

export function BidiIsolate({ as = "bdi", direction = "auto", children, ...props }) {
  return React.createElement(as, { ...props, dir: htmlDirection(direction) }, children);
}

export function isolateText(value, direction = "auto") {
  const normalized = normalizeBidiDirection(direction);
  const marker = normalized === "ltr" ? "\u2066" : normalized === "rtl" ? "\u2067" : "\u2068";
  return `${marker}${value}\u2069`;
}

function useMessageContext() {
  const context = useContext(MessageContext);
  if (context == null) throw new Error("MF2 React components must be used inside MessageProvider.");
  return context;
}

function requireMessage(catalog, id) {
  const entry = catalog?.get(id);
  if (entry == null) throw new Error(`Unknown MF2 message id: ${id}`);
  return entry;
}

function current(stack) {
  return stack[stack.length - 1];
}

function closeMarkup(stack, components, values) {
  if (stack.length <= 1) return;
  const frame = stack.pop();
  current(stack).children.push(renderMarkup(frame.part, frame.children, components, values));
}

function renderMarkup(part, children, components, values) {
  const component = components[part.name];
  const props = resolvePartProps(part, values);
  if (typeof component === "function") {
    return component({ ...props, children, part });
  }
  if (typeof component === "string") {
    return React.createElement(component, props, children);
  }
  return children;
}

function resolvePartProps(part, values) {
  return {
    ...resolveOptions(part.options ?? {}, values),
    ...resolveOptions(part.attributes ?? {}, values),
  };
}

function resolveOptions(options, values) {
  const props = {};
  for (const [name, value] of Object.entries(options)) {
    props[name] = value.type === "variable" ? values[value.name] : value.value;
  }
  return props;
}

function renderExpression(value, bidiIsolation, direction) {
  if (bidiIsolation === "element") {
    return React.createElement(BidiIsolate, { direction }, value);
  }
  return isolateExpression(value, bidiIsolation, direction);
}

function isolateExpression(value, bidiIsolation, direction) {
  if (bidiIsolation !== "default") return value;
  return isolateText(value, direction);
}

function htmlDirection(direction) {
  return normalizeBidiDirection(direction);
}

function normalizeBidiDirection(direction) {
  return direction === "ltr" || direction === "rtl" ? direction : "auto";
}

function resolveDirection(dir, locale) {
  if (dir === "locale") return textDirectionForLocale(locale);
  return dir === "ltr" || dir === "rtl" || dir === "auto" ? dir : "auto";
}

export function textDirectionForLocale(locale) {
  try {
    const maximized = new Intl.Locale(locale).maximize();
    const direction = maximized.textInfo?.direction;
    if (direction === "rtl" || direction === "ltr") return direction;
    if (RTL_SCRIPTS.has(maximized.script)) return "rtl";
  } catch {
    // Fall back to compact BCP47-prefix checks below.
  }
  const parts = String(locale ?? "").split(/[-_]/u);
  if (RTL_SCRIPTS.has(parts[1])) return "rtl";
  return RTL_LANGUAGES.has(parts[0]?.toLowerCase()) ? "rtl" : "ltr";
}
