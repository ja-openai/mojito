export function toHtmlLangTag(localeTag: string | null | undefined): string | undefined {
  const trimmed = localeTag?.trim();
  if (!trimmed) {
    return undefined;
  }
  return trimmed.replace(/_/g, '-');
}
