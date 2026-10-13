const nfc = (value) => value.normalize("NFC");

// Preserve unchanged subtrees. Literal contents are never normalized: only
// binding identifiers and metadata keys have canonical-equivalence semantics.
export function normalizeModel(value, error, keysAreNames = false) {
  if (!value || typeof value !== "object") return value;
  let result = value;
  const names = keysAreNames ? new Set() : null;
  for (const key of Object.keys(value)) {
    const child = value[key];
    const name = keysAreNames ? nfc(key) : key;
    if (names?.has(name))
      error(
        "duplicate-normalized-name",
        `Duplicate identifier ${name} after NFC normalization.`,
      );
    names?.add(name);
    const normalized =
      key === "name" && typeof child === "string"
        ? nfc(child)
        : normalizeModel(
            child,
            error,
            key === "options" || key === "attributes",
          );
    if (name === key && normalized === child) continue;
    if (result === value)
      result = Array.isArray(value) ? value.slice() : { ...value };
    if (name !== key) delete result[key];
    Object.defineProperty(result, name, {
      value: normalized,
      writable: true,
      enumerable: true,
      configurable: true,
    });
  }
  return result;
}

export function normalizeSignatures(functions, error) {
  if (functions === undefined) return undefined;
  if (!functions || typeof functions !== "object" || Array.isArray(functions)) {
    error(
      "invalid-function-signature",
      "Function capabilities must be an object.",
    );
    return {};
  }
  const normalizeKeys = (record) => {
    const result = new Map();
    for (const [key, value] of Object.entries(record)) {
      const name = nfc(key);
      if (result.has(name))
        error(
          "duplicate-normalized-name",
          `Duplicate capability identifier ${name} after NFC normalization.`,
        );
      result.set(name, value);
    }
    return Object.fromEntries(result);
  };
  const result = normalizeKeys(functions);
  for (const [name, signature] of Object.entries(result)) {
    if (
      signature?.options &&
      typeof signature.options === "object" &&
      !Array.isArray(signature.options)
    )
      result[name] = {
        ...signature,
        options: normalizeKeys(signature.options),
      };
  }
  return result;
}
