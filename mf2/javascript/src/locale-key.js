const MAX_LOCALE_KEY_LENGTH = 256;

export function canonicalLocaleKey(locale) {
  const value = String(locale ?? "");
  if (value.length > MAX_LOCALE_KEY_LENGTH) return "";
  const parts = value
    .trim()
    .replaceAll("_", "-")
    .split("-")
    .filter(Boolean);
  const output = [];
  for (let index = 0; index < parts.length; index += 1) {
    const part = parts[index];
    const lower = part.toLowerCase();
    if (part.length === 1) break;
    if (index === 0) {
      output.push(lower);
    } else if (part.length === 4 && /^[a-zA-Z]+$/.test(part)) {
      output.push(part[0].toUpperCase() + part.slice(1).toLowerCase());
    } else if ((part.length === 2 && /^[a-zA-Z]+$/.test(part)) || (part.length === 3 && /^\d+$/.test(part))) {
      output.push(part.toUpperCase());
    } else {
      output.push(lower);
    }
  }
  return output.join("-");
}

export function localeLookupChain(locale) {
  const key = canonicalLocaleKey(locale);
  if (!key) return [];
  const parts = key.split("-");
  const chain = [];
  for (let length = parts.length; length >= 1; length -= 1) {
    chain.push(parts.slice(0, length).join("-"));
  }
  return chain;
}

export function pluralLookupChain(locale, parents = {}) {
  return featureLookupChain(locale, parents);
}

export function featureLookupChain(locale, parents = {}) {
  const output = [];
  appendFeatureLookupChain(canonicalLocaleKey(locale), parents, output);
  return output;
}

function appendFeatureLookupChain(locale, parents, output) {
  let current = locale;
  while (current !== "") {
    if (current.length > MAX_LOCALE_KEY_LENGTH) return;
    if (output.includes(current)) return;
    output.push(current);
    const parent = parents[current];
    if (parent != null) appendFeatureLookupChain(parent, parents, output);
    current = structuralParent(current);
  }
}

function structuralParent(locale) {
  const index = locale.lastIndexOf("-");
  return index < 0 ? "" : locale.slice(0, index);
}
