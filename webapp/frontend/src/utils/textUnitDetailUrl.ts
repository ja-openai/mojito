export const CMS_AUTHORING_PATH = '/settings/system/content-cms';
export const CMS_ADMIN_PATH = `${CMS_AUTHORING_PATH}/admin`;

export type CmsTextUnitContext = {
  projectId: number;
  projectName: string;
  entryId: number;
  entryName: string;
  fieldId: number;
  fieldName: string;
};

type CmsAuthoringSelection = Pick<CmsTextUnitContext, 'projectId' | 'entryId' | 'fieldId'>;
type CmsSelection = Partial<CmsAuthoringSelection> & {
  localeTag?: string | null;
};

type CmsTextUnitDetailLink = {
  to: {
    pathname: string;
    search: string;
  };
  state: {
    from: string;
    cmsContext: CmsTextUnitContext;
  };
};

const CMS_AUTHORING_SELECTION_QUERY_KEYS = {
  projectId: 'projectId',
  entryId: 'entryId',
  fieldId: 'fieldId',
  localeTag: 'locale',
} as const;
const CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS = {
  projectName: 'cmsProject',
  entryName: 'cmsEntry',
  fieldName: 'cmsField',
} as const;

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

export const buildCmsAuthoringPath = (
  { projectId, entryId, fieldId }: CmsAuthoringSelection,
  localeTag?: string | null,
) => {
  return buildCmsSelectionPath(CMS_AUTHORING_PATH, { projectId, entryId, fieldId, localeTag });
};

export const buildCmsSelectionPath = (
  pathname: string,
  { projectId, entryId, fieldId, localeTag }: CmsSelection,
) => {
  const searchParams = new URLSearchParams();
  if (projectId != null) {
    searchParams.set(CMS_AUTHORING_SELECTION_QUERY_KEYS.projectId, String(projectId));
  }
  if (entryId != null) {
    searchParams.set(CMS_AUTHORING_SELECTION_QUERY_KEYS.entryId, String(entryId));
  }
  if (fieldId != null) {
    searchParams.set(CMS_AUTHORING_SELECTION_QUERY_KEYS.fieldId, String(fieldId));
  }
  const trimmedLocaleTag = localeTag?.trim();
  if (trimmedLocaleTag) {
    searchParams.set(CMS_AUTHORING_SELECTION_QUERY_KEYS.localeTag, trimmedLocaleTag);
  }
  const search = searchParams.toString();
  return search ? `${pathname}?${search}` : pathname;
};

export const buildCmsTextUnitDetailLink = (
  tmTextUnitId: number,
  context: CmsTextUnitContext,
  localeTag?: string | null,
): CmsTextUnitDetailLink => {
  const returnTo = buildCmsAuthoringPath(context, localeTag);
  const searchParams = new URLSearchParams();
  const trimmedLocaleTag = localeTag?.trim();
  if (trimmedLocaleTag) {
    searchParams.set('locale', trimmedLocaleTag);
  }
  searchParams.set('returnTo', returnTo);
  searchParams.set(CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS.projectName, context.projectName);
  searchParams.set(CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS.entryName, context.entryName);
  searchParams.set(CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS.fieldName, context.fieldName);
  return {
    to: {
      pathname: `/text-units/${encodeURIComponent(String(tmTextUnitId))}`,
      search: `?${searchParams.toString()}`,
    },
    state: {
      from: returnTo,
      cmsContext: context,
    },
  };
};

export const getCmsAuthoringSelectionFromSearchParams = (
  searchParams: URLSearchParams,
): {
  projectId: number | null;
  entryId: number | null;
  fieldId: number | null;
  localeTag: string | null;
} => ({
  projectId: parsePositiveInteger(searchParams.get(CMS_AUTHORING_SELECTION_QUERY_KEYS.projectId)),
  entryId: parsePositiveInteger(searchParams.get(CMS_AUTHORING_SELECTION_QUERY_KEYS.entryId)),
  fieldId: parsePositiveInteger(searchParams.get(CMS_AUTHORING_SELECTION_QUERY_KEYS.fieldId)),
  localeTag: searchParams.get(CMS_AUTHORING_SELECTION_QUERY_KEYS.localeTag)?.trim() || null,
});

export const isCmsAuthoringPath = (path: string | null | undefined) =>
  path === CMS_AUTHORING_PATH || path?.startsWith(`${CMS_AUTHORING_PATH}?`) === true;

export const getCmsTextUnitReturnTo = (searchParams: URLSearchParams) => {
  const returnTo = searchParams.get('returnTo')?.trim() ?? null;
  return isCmsAuthoringPath(returnTo) ? returnTo : null;
};

export const getCmsTextUnitContextFromSearchParams = (
  searchParams: URLSearchParams,
): CmsTextUnitContext | null => {
  const returnTo = getCmsTextUnitReturnTo(searchParams);
  if (returnTo == null) {
    return null;
  }

  const authoringSelection = getCmsAuthoringSelectionFromSearchParams(
    new URL(returnTo, window.location.origin).searchParams,
  );
  const context = {
    projectId: authoringSelection.projectId,
    projectName: searchParams.get(CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS.projectName)?.trim(),
    entryId: authoringSelection.entryId,
    entryName: searchParams.get(CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS.entryName)?.trim(),
    fieldId: authoringSelection.fieldId,
    fieldName: searchParams.get(CMS_TEXT_UNIT_CONTEXT_QUERY_KEYS.fieldName)?.trim(),
  };

  return isCmsTextUnitContext(context) ? context : null;
};

export const isCmsTextUnitContext = (value: unknown): value is CmsTextUnitContext => {
  if (typeof value !== 'object' || value == null) {
    return false;
  }

  const context = value as Record<string, unknown>;
  return (
    isPositiveInteger(context.projectId) &&
    isNonBlankString(context.projectName) &&
    isPositiveInteger(context.entryId) &&
    isNonBlankString(context.entryName) &&
    isPositiveInteger(context.fieldId) &&
    isNonBlankString(context.fieldName)
  );
};

export const formatCmsTextUnitContext = (context: CmsTextUnitContext) =>
  `${context.projectName} / ${context.entryName} / ${context.fieldName}`;

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

function parsePositiveInteger(value: string | null) {
  const parsedValue = value == null ? NaN : Number(value);
  return isPositiveInteger(parsedValue) ? parsedValue : null;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
