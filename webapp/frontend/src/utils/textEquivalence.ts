export function textsAreEquivalent(
  first: string | null | undefined,
  second: string | null | undefined,
): boolean {
  return normalizeEquivalentText(first) === normalizeEquivalentText(second);
}

function normalizeEquivalentText(value: string | null | undefined): string | null {
  return value == null ? null : value.normalize('NFC');
}
