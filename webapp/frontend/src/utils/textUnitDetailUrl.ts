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

export const buildReviewProjectTextUnitUrl = (
  reviewProjectId: number,
  selectedTextUnitId: number,
) => {
  const url = new URL(
    `/review-projects/${encodeURIComponent(String(reviewProjectId))}`,
    window.location.origin,
  );
  url.searchParams.set('tu', String(selectedTextUnitId));
  return url.toString();
};
