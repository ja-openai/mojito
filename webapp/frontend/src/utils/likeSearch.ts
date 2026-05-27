export const CONTAINS_SEARCH_HELPER = 'Find text that includes what you type';

export const ILIKE_SEARCH_HELPER =
  'Use % for any text and _ for one character, e.g. %term%; use \\ before % or _ to search for these characters, e.g. %\\%%';

export const escapeLikePattern = (value: string) =>
  value.replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_');

export const containsLikePattern = (value: string) => `%${escapeLikePattern(value)}%`;
