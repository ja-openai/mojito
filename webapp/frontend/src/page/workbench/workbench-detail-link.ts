import type { TextUnitSearchRequest } from '../../api/text-units';
import type { WorkbenchResultSortDirection, WorkbenchResultSortField } from './workbench-types';

type WorkbenchDetailContext = {
  searchRequest: TextUnitSearchRequest;
  resultSortField: WorkbenchResultSortField;
  resultSortDirection: WorkbenchResultSortDirection;
};

const sortFields = [
  'tmTextUnitId',
  'source',
  'translation',
  'sourceCreatedDate',
  'translationCreatedDate',
  'assetPath',
  'comment',
];
const searchFields = [
  'stringId',
  'source',
  'target',
  'comment',
  'asset',
  'location',
  'pluralFormOther',
  'tmTextUnitIds',
];
const searchTypes = ['exact', 'contains', 'ilike', 'regex'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isIdArray(value: unknown): value is number[] {
  return (
    Array.isArray(value) &&
    value.every((id: unknown) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0)
  );
}

function isOneOf(value: unknown, options: readonly string[]): value is string {
  return typeof value === 'string' && options.includes(value);
}

function isSearchRequest(value: unknown): value is TextUnitSearchRequest {
  if (
    !isRecord(value) ||
    !isIdArray(value.repositoryIds) ||
    !Array.isArray(value.localeTags) ||
    !value.localeTags.every((locale) => typeof locale === 'string')
  ) {
    return false;
  }
  if (value.tmTextUnitIds !== undefined && !isIdArray(value.tmTextUnitIds)) return false;
  if (
    [
      'searchText',
      'statusFilter',
      'tmTextUnitCreatedBefore',
      'tmTextUnitCreatedAfter',
      'tmTextUnitVariantCreatedBefore',
      'tmTextUnitVariantCreatedAfter',
    ].some((field) => value[field] !== undefined && typeof value[field] !== 'string') ||
    ['doNotTranslateFilter', 'orderedByTextUnitId'].some(
      (field) => value[field] !== undefined && typeof value[field] !== 'boolean',
    ) ||
    (value.limit !== undefined &&
      (!Number.isSafeInteger(value.limit) || Number(value.limit) < 1)) ||
    (value.offset !== undefined &&
      (!Number.isSafeInteger(value.offset) || Number(value.offset) < 0)) ||
    (value.usedFilter !== undefined &&
      value.usedFilter !== 'USED' &&
      value.usedFilter !== 'UNUSED') ||
    (value.glossaryStatusFilter !== undefined &&
      !isOneOf(value.glossaryStatusFilter, [
        'ALL',
        'APPROVED',
        'CANDIDATE',
        'REJECTED',
        'DEPRECATED',
      ])) ||
    (value.searchAttribute !== undefined && !isOneOf(value.searchAttribute, searchFields)) ||
    (value.searchType !== undefined && !isOneOf(value.searchType, searchTypes))
  ) {
    return false;
  }
  if (value.textSearch !== undefined) {
    const textSearch = value.textSearch;
    if (
      !isRecord(textSearch) ||
      (textSearch.operator !== 'AND' && textSearch.operator !== 'OR') ||
      !Array.isArray(textSearch.predicates) ||
      !textSearch.predicates.every(
        (predicate: unknown) =>
          isRecord(predicate) &&
          isOneOf(predicate.field, searchFields) &&
          isOneOf(predicate.searchType, searchTypes) &&
          typeof predicate.value === 'string',
      )
    ) {
      return false;
    }
  }
  return true;
}

export function buildWorkbenchDetailHash(
  searchRequest: TextUnitSearchRequest | null,
  resultSortField: WorkbenchResultSortField,
  resultSortDirection: WorkbenchResultSortDirection,
): string {
  if (!searchRequest) return '';
  const context: WorkbenchDetailContext = { searchRequest, resultSortField, resultSortDirection };
  return `#${new URLSearchParams({ workbench: JSON.stringify(context) }).toString()}`;
}

export function readWorkbenchDetailContext(hash: string): WorkbenchDetailContext | null {
  try {
    const encoded = new URLSearchParams(hash.replace(/^#/, '')).get('workbench');
    if (!encoded) return null;
    const context: unknown = JSON.parse(encoded);
    if (
      !isRecord(context) ||
      !isSearchRequest(context.searchRequest) ||
      !isOneOf(context.resultSortField, sortFields) ||
      !isOneOf(context.resultSortDirection, ['default', 'asc', 'desc'])
    ) {
      return null;
    }
    return context as WorkbenchDetailContext;
  } catch {
    return null;
  }
}
