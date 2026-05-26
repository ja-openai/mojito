export const CONTAINS_SEARCH_HELPER = 'Case-insensitive literal substring; type % and _ directly';

export const ILIKE_SEARCH_HELPER =
  'Case-insensitive LIKE pattern; use % wildcards and \\ to escape, e.g. %\\%%';

export const escapeLikePattern = (value: string) =>
  value.replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_');

export const containsLikePattern = (value: string) => `%${escapeLikePattern(value)}%`;
