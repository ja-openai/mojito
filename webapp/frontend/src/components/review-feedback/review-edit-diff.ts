/** Preview only: the server classifies and stores the actual acceptance independently. */
export function isMaterialReviewEdit(baseline: string | null, target: string): boolean {
  if (baseline === null) return false;
  const normalize = (s: string) => s.normalize('NFC').replace(/\s+/gu, ' ').trim();
  const a = normalize(baseline),
    b = normalize(target);
  if (a === b) return false;
  const protectedParts = (s: string) =>
    s.match(
      /\{[^{}]*\}|%[0-9$]*[a-zA-Z]|<[^>]+>|https?:\/\/[^\s<>]+|\p{N}+(?:[.,]\p{N}+)*|!?\[[^\]]*\]\([^)]*\)|\*\*|__|`+/gu,
    ) ?? [];
  if (JSON.stringify(protectedParts(a)) !== JSON.stringify(protectedParts(b))) return true;
  const styleNeutral = (s: string) => s.replace(/[\p{P}\s]/gu, '').toLowerCase();
  return styleNeutral(a) !== styleNeutral(b);
}
