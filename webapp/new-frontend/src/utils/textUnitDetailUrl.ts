export const buildTextUnitDetailUrl = (tmTextUnitId: number, localeTag?: string | null) => {
  const url = new URL(
    `/text-units/${encodeURIComponent(String(tmTextUnitId))}`,
    window.location.origin,
  );
  const trimmedLocaleTag = localeTag?.trim();
  if (trimmedLocaleTag) {
    url.searchParams.set('locale', trimmedLocaleTag);
  }
  return url.toString();
};
