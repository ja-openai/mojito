import { canonicalLocaleKey } from "./locale-key.js";
import * as data from "./locale_direction_data.js";

const sets = Object.fromEntries(Object.entries(data).map(([name, value]) => [name, new Set(value.trim().split(/\s+/))]));

export function localeIsLtr(locale) {
  let parts;
  try { parts = canonicalLocaleKey(locale).split("-"); } catch { return null; }
  const language = parts[0];
  let region = null;
  for (const part of parts.slice(1)) {
    if (/^[A-Z][a-z]{3}$/.test(part)) return sets.LTR_SCRIPTS.has(part) ? true : sets.RTL_SCRIPTS.has(part) ? false : null;
    if (/^(?:[A-Z]{2}|[0-9]{3})$/.test(part)) region = part;
  }
  if (region) {
    if (sets.LTR_REGION_OVERRIDES.has(`${language}-${region}`)) return true;
    if (sets.RTL_REGION_OVERRIDES.has(`${language}-${region}`)) return false;
  }
  if (language === "und") return null;
  return sets.LTR_LANGUAGES.has(language) ? true : sets.RTL_LANGUAGES.has(language) ? false : null;
}
