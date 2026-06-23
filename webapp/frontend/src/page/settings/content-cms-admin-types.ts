import {
  type ApiCmsContentType,
  type ApiCmsEntry,
  type ApiCmsEntryStatus,
  type ApiCmsFieldMapping,
  type ApiCmsFieldType,
  type ApiCmsLocaleCompleteness,
  type ApiCmsVariantStatus,
} from '../../api/content-cms';

export type ProjectDraft = {
  projectKey: string;
  name: string;
  description: string;
  repositoryId: string;
  assetPath: string;
  deliveryHint: string;
  enabled: boolean;
};

export type ContentTypeDraft = {
  typeKey: string;
  name: string;
  description: string;
  schemaVersion: string;
  metadataSchemaJson: string;
};

export type FieldDraft = {
  contentTypeId: string;
  fieldKey: string;
  name: string;
  description: string;
  sourceContent: string;
  sourceComment: string;
  fieldType: ApiCmsFieldType;
  required: boolean;
  sortOrder: string;
};

export type EntryDraft = {
  contentTypeId: string;
  entryKey: string;
  name: string;
  description: string;
  status: ApiCmsEntryStatus;
  metadataJson: string;
};

export type SourceCopyDraft = {
  sourceContent: string;
  sourceComment: string;
};

export type FirstCopyBlockDraft = {
  entryKey: string;
  entryName: string;
  entryDescription: string;
  fieldKey: string;
  sourceContent: string;
  sourceComment: string;
};

export type VariantDraft = {
  entryId: string;
  variantKey: string;
  name: string;
  candidateGroupKey: string;
  status: ApiCmsVariantStatus;
  metadataJson: string;
  sortOrder: string;
};

export type MappingSource = 'GENERATED' | 'STRING_ID' | 'TM_TEXT_UNIT_ID';

export type MappingDraft = {
  entryId: string;
  variantId: string;
  fieldId: string;
  mappingSource: MappingSource;
  tmTextUnitId: string;
  stringId: string;
  sourceContent: string;
  sourceComment: string;
};

export type AuthoringMappingDraftState = {
  draft: MappingDraft;
  dirty: boolean;
  expectedVersion: number | null;
  savedDraft: MappingDraft;
  staleSavedDraft: MappingDraft | null;
};

export type AuthoringFieldDraftState = {
  draft: FieldDraft;
  dirty: boolean;
  expectedVersion: number;
  savedDraft: FieldDraft;
  staleSavedDraft: FieldDraft | null;
};

export type ProjectEditDraft = Pick<
  ProjectDraft,
  'name' | 'description' | 'deliveryHint' | 'enabled'
>;

export function hasCmsTargetLocales(locales: ApiCmsLocaleCompleteness[], sourceLocale: string) {
  return locales.some((locale) => locale.localeTag !== sourceLocale);
}

export function hasReadyCmsTargetLocales(
  locales: ApiCmsLocaleCompleteness[],
  sourceLocale: string,
) {
  const targetLocales = locales.filter((locale) => locale.localeTag !== sourceLocale);
  return targetLocales.length > 0 && targetLocales.every((locale) => locale.complete);
}

export const initialProjectDraft: ProjectDraft = {
  projectKey: '',
  name: '',
  description: '',
  repositoryId: '',
  assetPath: '',
  deliveryHint: 'BLOB_CDN',
  enabled: true,
};

export const initialContentTypeDraft: ContentTypeDraft = {
  typeKey: '',
  name: '',
  description: '',
  schemaVersion: '1',
  metadataSchemaJson: '',
};

export const initialFieldDraft: FieldDraft = {
  contentTypeId: '',
  fieldKey: '',
  name: '',
  description: '',
  sourceContent: '',
  sourceComment: '',
  fieldType: 'TEXT',
  required: true,
  sortOrder: '0',
};

export const initialEntryDraft: EntryDraft = {
  contentTypeId: '',
  entryKey: '',
  name: '',
  description: '',
  status: 'DRAFT',
  metadataJson: '',
};

export const initialSourceCopyDraft: SourceCopyDraft = {
  sourceContent: '',
  sourceComment: '',
};

export const initialFirstCopyBlockDraft: FirstCopyBlockDraft = {
  entryKey: 'first-block',
  entryName: '',
  entryDescription: '',
  fieldKey: 'copy',
  sourceContent: '',
  sourceComment: '',
};

export const onboardingProjectStarter = {
  name: 'Growth email copy',
  description: 'Signup and onboarding messages',
};

export const welcomeEmailCopyBlockStarter = {
  entryName: 'Welcome email',
  entryDescription: 'Signup confirmation email',
  sourceContent: 'Welcome to Acme. Start your first project in minutes.',
  sourceComment: 'Friendly welcome sentence. Keep Acme untranslated.',
};

export const initialVariantDraft: VariantDraft = {
  entryId: '',
  variantKey: '',
  name: '',
  candidateGroupKey: '',
  status: 'CANDIDATE',
  metadataJson: '',
  sortOrder: '0',
};

export const initialMappingDraft: MappingDraft = {
  entryId: '',
  variantId: '',
  fieldId: '',
  mappingSource: 'GENERATED',
  tmTextUnitId: '',
  stringId: '',
  sourceContent: '',
  sourceComment: '',
};

export const initialProjectEditDraft: ProjectEditDraft = {
  name: '',
  description: '',
  deliveryHint: 'BLOB_CDN',
  enabled: true,
};

export const normalizeText = (value: string) => {
  const normalized = value.trim();
  return normalized ? normalized : null;
};

export const normalizeSourceContent = (value: string) => (value.trim() ? value : null);

export function slugifyCmsKey(value: string, fallback: string) {
  const normalized = value
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return normalized || fallback;
}

export function updateSuggestedCmsKey(
  currentKey: string,
  currentName: string,
  nextName: string,
  fallback: string,
) {
  return currentKey.trim().length === 0 || currentKey === slugifyCmsKey(currentName, fallback)
    ? slugifyCmsKey(nextName, fallback)
    : currentKey;
}

export function buildGeneratedCmsStringId(
  projectKey: string | null | undefined,
  entryKey: string | null | undefined,
  variantKey: string | null | undefined,
  fieldKey: string | null | undefined,
) {
  if (!projectKey || !entryKey || !variantKey || !fieldKey) {
    return null;
  }
  return `cms.${projectKey}.${entryKey}.${variantKey}.${fieldKey}`;
}

export function buildMappingDraft({
  projectKey,
  entry,
  variant,
  field,
  mapping,
}: {
  projectKey: string | null | undefined;
  entry: ApiCmsEntry;
  variant: ApiCmsEntry['variants'][number];
  field: ApiCmsContentType['fields'][number];
  mapping: ApiCmsFieldMapping | null;
}): MappingDraft {
  const generatedStringId = buildGeneratedCmsStringId(
    projectKey,
    entry.entryKey,
    variant.variantKey,
    field.fieldKey,
  );
  const mappingSource =
    mapping == null || mapping.stringId === generatedStringId ? 'GENERATED' : 'TM_TEXT_UNIT_ID';
  return {
    entryId: String(entry.id),
    variantId: String(variant.id),
    fieldId: String(field.id),
    mappingSource,
    tmTextUnitId: mappingSource === 'TM_TEXT_UNIT_ID' ? String(mapping?.tmTextUnitId ?? '') : '',
    stringId: '',
    sourceContent: mapping?.sourceContent ?? '',
    sourceComment: mapping?.sourceComment ?? '',
  };
}

export const parseRequiredNumber = (value: string, label: string) => {
  const parsed = Number(value);
  if (!value.trim() || Number.isNaN(parsed)) {
    throw new Error(`${label} is required.`);
  }
  return parsed;
};

export const parseOptionalNumber = (value: string) => {
  if (!value.trim()) {
    return null;
  }
  const parsed = Number(value);
  return Number.isNaN(parsed) ? null : parsed;
};

export const formatBool = (value: boolean) => (value ? 'Yes' : 'No');

export function findContentType(contentTypes: ApiCmsContentType[], contentTypeId: number) {
  return contentTypes.find((contentType) => contentType.id === contentTypeId) ?? null;
}

export function findEntry(entries: ApiCmsEntry[] | undefined, entryId: string) {
  const parsed = parseOptionalNumber(entryId);
  if (parsed == null || entries == null) {
    return null;
  }
  return entries.find((entry) => entry.id === parsed) ?? null;
}

export function findVariant(entry: ApiCmsEntry | null, variantId: string) {
  const parsed = parseOptionalNumber(variantId);
  if (parsed == null || entry == null) {
    return null;
  }
  return entry.variants.find((variant) => variant.id === parsed) ?? null;
}
