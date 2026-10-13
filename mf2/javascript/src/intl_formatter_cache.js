// Registry-scoped LRU: retain only normalized formatting options and Intl
// instances, never models, source text, arguments, or formatted results.
export function createIntlFormatterCache(capacity = 64) {
  if (!Number.isInteger(capacity) || capacity < 0 || capacity > 1024) {
    throw new RangeError("formatterCacheSize must be an integer between 0 and 1024");
  }
  const entries = new Map();
  return (kind, locale, options) => {
    if (capacity === 0) return new Intl[kind](locale, options);
    // Callers construct option objects in a fixed order, after validating them.
    const key = JSON.stringify([kind, locale, options]);
    let formatter = entries.get(key);
    if (formatter !== undefined) {
      entries.delete(key);
    } else {
      formatter = new Intl[kind](locale, options);
      if (entries.size === capacity) entries.delete(entries.keys().next().value);
    }
    entries.set(key, formatter);
    return formatter;
  };
}
