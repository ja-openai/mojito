// Markup is a name-only contract. Options/attributes are model descriptors, never
// React props. Validate every token before invoking any application callbacks.
export function validateMarkupParts(parts, allowedNames) {
  const allowed = new Set(allowedNames);
  const stack = [];
  for (const part of parts) {
    if (part.type !== "markup") continue;
    if (!allowed.has(part.name))
      throw new Error(`Unknown MF2 markup: ${part.name}`);
    if (
      Object.keys(part.options ?? {}).length ||
      Object.keys(part.attributes ?? {}).length
    )
      throw new Error(
        `MF2 markup options and attributes are unsupported: ${part.name}`,
      );
    if (part.kind === "open") stack.push(part.name);
    else if (part.kind === "close") {
      if (stack.pop() !== part.name)
        throw new Error(`Mismatched MF2 markup: ${part.name}`);
    } else if (part.kind !== "standalone")
      throw new Error(`Invalid MF2 markup kind: ${part.kind}`);
  }
  if (stack.length) throw new Error(`Unclosed MF2 markup: ${stack.at(-1)}`);
}

export function validateMessageMarkup(model, allowedNames = []) {
  const patterns =
    model.type === "select"
      ? model.variants.map((variant) => variant.value)
      : [model.pattern];
  for (const pattern of patterns) validateMarkupParts(pattern, allowedNames);
}
