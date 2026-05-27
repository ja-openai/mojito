export function mf2PlaceholderCompletionContext(source, pos) {
  const before = source.slice(Math.max(0, pos - 80), pos);
  const match = before.match(/(?:\{\$?|\$)([\p{L}\p{N}_-]*)$/u);
  if (!match) return null;
  return {
    from: pos - match[0].length,
    query: match[1] ?? "",
  };
}

export function mf2PlaceholderCompletionNames(names, query) {
  const normalizedQuery = (query ?? "").toLocaleLowerCase();
  return [...new Set(names)].filter(
    (name) => !normalizedQuery || name.toLocaleLowerCase().includes(normalizedQuery),
  );
}

export function mf2PlaceholderNamesFromText(source) {
  return [...new Set([...source.matchAll(/\$([\p{L}_][\p{L}\p{N}_-]*)/gu)].map((match) => match[1]))].sort();
}

export function mf2PlaceholderCompletionReplacement(source, from, to, name) {
  const insert = `{$${name}}`;
  return {
    from,
    to: source.slice(to, to + 1) === "}" ? to + 1 : to,
    insert,
    cursor: from + insert.length,
  };
}
