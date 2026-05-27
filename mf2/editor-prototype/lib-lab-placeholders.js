export function placeholderCountsFromSource(pattern) {
  const counts = new Map();
  const expressionPattern = /\$([A-Za-z_][\w.-]*)/gu;
  for (const match of String(pattern ?? "").matchAll(expressionPattern)) {
    counts.set(match[1], (counts.get(match[1]) ?? 0) + 1);
  }
  return counts;
}

export function countsToRequirements(counts) {
  return Array.from(counts, ([name, count]) => ({ name, count }));
}

export function placeholderRequirementCounts(requirements) {
  return new Map(requirements.map((item) => [item.name, item.count]));
}

export function missingRequiredPlaceholders(required, currentCounts) {
  return required
    .map((item) => ({
      name: item.name,
      count: item.count - (currentCounts.get(item.name) ?? 0),
    }))
    .filter((item) => item.count > 0);
}

export function placeholderRequirementLabel(requirements) {
  return requirements.map((item) => `{$${item.name}}${item.count > 1 ? ` x${item.count}` : ""}`).join(", ");
}

export function sourceVariantPlaceholdersForSignature(sourceRequirements, signature, fallbackPattern, hasSourceContract) {
  const baseline = sourceRequirements?.find((item) => item.signature === signature);
  if (baseline) return baseline.requirements;
  return hasSourceContract ? [] : countsToRequirements(placeholderCountsFromSource(fallbackPattern));
}

export function tokenPaletteItems(names, limit = 8) {
  const unique = Array.from(new Set(names ?? [])).sort();
  return {
    visible: unique.slice(0, limit),
    overflow: Math.max(0, unique.length - limit),
  };
}

export function splitPatternTokens(pattern) {
  const source = String(pattern ?? "");
  const parts = [];
  let index = 0;
  const tokenPattern = /\{(#|\/)([A-Za-z_][\w.-]*)([^}]*)\}|\{\$([A-Za-z_][\w.-]*)\}/gu;
  for (const match of source.matchAll(tokenPattern)) {
    if (match.index > index) parts.push({ type: "text", value: source.slice(index, match.index) });
    if (match[4]) {
      parts.push({ type: "placeholder", name: match[4] });
    } else {
      const kind = match[1] === "/" ? "close" : match[3]?.trimEnd().endsWith("/") ? "standalone" : "open";
      parts.push({ type: "markup", name: match[2], kind, source: match[0] });
    }
    index = match.index + match[0].length;
  }
  if (index < source.length) parts.push({ type: "text", value: source.slice(index) });
  return parts;
}
