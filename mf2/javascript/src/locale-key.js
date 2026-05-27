export function canonicalLocaleKey(locale) {
  const parts = String(locale ?? "")
    .trim()
    .replaceAll("_", "-")
    .split("-")
    .filter(Boolean);
  const output = [];
  for (let index = 0; index < parts.length; index += 1) {
    const part = parts[index];
    const lower = part.toLowerCase();
    if (lower === "u" || lower === "x") break;
    if (index === 0) {
      output.push(lower);
    } else if (part.length === 4 && /^[a-zA-Z]+$/.test(part)) {
      output.push(part[0].toUpperCase() + part.slice(1).toLowerCase());
    } else if ((part.length === 2 && /^[a-zA-Z]+$/.test(part)) || (part.length === 3 && /^\d+$/.test(part))) {
      output.push(part.toUpperCase());
    } else {
      output.push(part);
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
  const output = [];
  for (const candidate of localeLookupChain(locale)) {
    output.push(candidate);
    const parent = parents[candidate];
    if (parent != null && !output.includes(parent)) output.push(parent);
  }
  return output;
}
