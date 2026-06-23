import '../../app.css';
import './admin-content-cms-page.css';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  type ComponentProps,
  type FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { MemoryRouter } from 'react-router-dom';

import type {
  ApiCmsContentType,
  ApiCmsContentTypeField,
  ApiCmsEntry,
  ApiCmsEntryCompleteness,
  ApiCmsFieldMapping,
  ApiCmsProjectCompleteness,
  ApiCmsProjectSummary,
  ApiCmsPublishSnapshot,
  ApiCmsVariant,
} from '../../api/content-cms';
import type { ApiTextUnit } from '../../api/text-units';
import { ConfirmModal } from '../../components/ConfirmModal';
import { UserContext } from '../../hooks/useUser';
import { createLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import {
  type AuthoringRecovery,
  CmsEntryWorkspace,
  CopyCollectionsSidebarToggle,
  FirstWritingSpaceForm,
  PublishForm,
  type ReleaseHistoryChange,
  type ReleaseRecovery,
  type ReleaseRepairTarget,
  type ReleaseReviewReturnMode,
  type ReleaseSuccess,
  StartWritingSpaceButton,
} from './content-cms-admin-forms';
import { type CopyFieldFocusTarget, queueCopyFieldScroll } from './content-cms-admin-scroll';
import {
  type AuthoringFieldDraftState,
  type AuthoringMappingDraftState,
  buildMappingDraft,
  type EntryDraft,
  initialEntryDraft,
  initialFirstCopyBlockDraft,
  initialMappingDraft,
  initialProjectDraft,
  type MappingDraft,
} from './content-cms-admin-types';
import { type ContentCmsPreviewMode, getContentCmsPreviewMode } from './content-cms-preview-mode';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const cmsPreviewCopyCollectionsSidebarBodyId = 'content-cms-preview-copy-collections-sidebar-body';

type PreviewWorkspaceProps = ComponentProps<typeof CmsEntryWorkspace>;

type PendingPreviewInlineTranslationFieldSwitch = {
  sourceFieldId: string;
  targetFieldId: string;
  targetFieldName: string;
  focusTarget: CopyFieldFocusTarget;
  reason: 'dirty-translation' | 'refresh-error';
};

type PreviewReviewTarget = {
  requestKey: number;
  entryId: string | null;
  fieldId: string | null;
  localeTag: string | null;
  repairTarget: ReleaseRepairTarget | null;
  reviewReason: string | null;
  reviewReturnMode: ReleaseReviewReturnMode | null;
  lastReleasedSourceContent: string | null;
  lastReleasedTranslationContent: string | null;
  returnToRelease: boolean;
  refreshTranslation: boolean;
  translationRepairSaved: boolean;
  translationReconnectErrorMessage: string | null;
};

const initialPreviewReviewTarget: PreviewReviewTarget = {
  requestKey: 0,
  entryId: null,
  fieldId: null,
  localeTag: null,
  repairTarget: null,
  reviewReason: null,
  reviewReturnMode: null,
  lastReleasedSourceContent: null,
  lastReleasedTranslationContent: null,
  returnToRelease: false,
  refreshTranslation: false,
  translationRepairSaved: false,
  translationReconnectErrorMessage: null,
};

const resolvePreviewLocaleDisplayName = createLocaleDisplayNameResolver();

const previewTargetLocaleSaveFailure = {
  kind: 'target-locales',
  message:
    'Could not add translation languages. Try again. If it keeps failing, ask an admin to check language setup.',
} satisfies Extract<AuthoringRecovery, { kind: 'target-locales' }>;

const previewTargetLocaleConflictRecovery = {
  kind: 'target-locales',
  message:
    'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
} satisfies Extract<AuthoringRecovery, { kind: 'target-locales' }>;

const previewTargetLocaleConflictSettledRecovery = {
  kind: 'target-locales',
  message:
    'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
} satisfies Extract<AuthoringRecovery, { kind: 'target-locales' }>;

const previewTargetLocaleConflictRefreshFailedRecovery = {
  kind: 'target-locales',
  message:
    'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
} satisfies Extract<AuthoringRecovery, { kind: 'target-locales' }>;

const previewAudit = {
  createdDate: '2026-06-10T16:00:00Z',
  lastModifiedDate: '2026-06-10T16:05:00Z',
  createdByUsername: 'preview-author',
  lastModifiedByUsername: 'preview-editor',
};

const previewProject: ApiCmsProjectSummary = {
  id: 1,
  entityVersion: 3,
  audit: previewAudit,
  projectKey: 'growth-email-copy',
  name: 'Growth email copy',
  description: 'Signup and onboarding messages',
  enabled: true,
  repository: { id: 10, name: 'growth-email-copy' },
  asset: { id: 11, path: 'cms/growth-email-copy.json' },
  deliveryHint: 'BLOB_CDN',
};

const previewBaseField: ApiCmsContentTypeField = {
  id: 201,
  entityVersion: 4,
  audit: previewAudit,
  contentTypeId: 101,
  fieldKey: 'headline',
  name: 'Headline',
  description: 'Hero headline',
  fieldType: 'TEXT',
  localizable: true,
  required: true,
  sortOrder: 0,
};

const previewCtaField: ApiCmsContentTypeField = {
  id: 202,
  entityVersion: 2,
  audit: previewAudit,
  contentTypeId: 101,
  fieldKey: 'cta_label',
  name: 'CTA label',
  description: 'Primary action',
  fieldType: 'TEXT',
  localizable: true,
  required: true,
  sortOrder: 1,
};

const previewContentType: ApiCmsContentType = {
  id: 101,
  entityVersion: 2,
  audit: previewAudit,
  projectId: previewProject.id,
  typeKey: 'welcome-email',
  name: 'Welcome email',
  description: 'Signup confirmation email',
  schemaVersion: 1,
  metadataSchemaJson: null,
  fields: [previewBaseField, previewCtaField],
};

const previewBaseMapping: ApiCmsFieldMapping = {
  id: 401,
  entityVersion: 5,
  audit: previewAudit,
  variantId: 301,
  fieldId: previewBaseField.id,
  fieldKey: previewBaseField.fieldKey,
  tmTextUnitId: 601,
  stringId: 'cms.growth-email-copy.welcome-email.default.headline',
  sourceContent: 'Welcome to Acme. Start your first project in minutes.',
  sourceComment: 'Friendly welcome headline. Keep Acme untranslated.',
};

const previewCtaMapping: ApiCmsFieldMapping = {
  id: 402,
  entityVersion: 1,
  audit: previewAudit,
  variantId: 301,
  fieldId: previewCtaField.id,
  fieldKey: previewCtaField.fieldKey,
  tmTextUnitId: 602,
  stringId: 'cms.growth-email-copy.welcome-email.default.cta_label',
  sourceContent: 'Start now',
  sourceComment: 'Short action label. Keep it concise.',
};

const previewBaseVariant: ApiCmsVariant = {
  id: 301,
  entityVersion: 2,
  audit: previewAudit,
  entryId: 501,
  variantKey: 'default',
  name: 'Default',
  candidateGroupKey: null,
  status: 'CONTROL',
  metadataJson: null,
  sortOrder: 0,
  fieldMappings: [previewBaseMapping, previewCtaMapping],
};

const previewBaseEntry: ApiCmsEntry = {
  id: 501,
  entityVersion: 4,
  audit: previewAudit,
  projectId: previewProject.id,
  contentTypeId: previewContentType.id,
  entryKey: 'welcome-email',
  name: 'Welcome email',
  description: 'Signup confirmation email',
  status: 'DRAFT',
  metadataJson: null,
  variants: [previewBaseVariant],
};

const previewUnmappedEntry: ApiCmsEntry = {
  ...previewBaseEntry,
  id: 502,
  entityVersion: 1,
  entryKey: 'follow-up-email',
  name: 'Follow-up email',
  description: 'Used after signup',
  variants: [
    {
      ...previewBaseVariant,
      id: 302,
      entityVersion: 1,
      entryId: 502,
      fieldMappings: [],
    },
  ],
};

const previewFollowUpMapping: ApiCmsFieldMapping = {
  ...previewBaseMapping,
  id: 703,
  variantId: previewUnmappedEntry.variants[0].id,
  tmTextUnitId: 603,
  stringId: 'cms.growth-email-copy.follow-up-email.default.headline',
  sourceContent: 'Keep building with your next project.',
  sourceComment: 'Follow-up headline after signup.',
};

const previewFollowUpCtaMapping: ApiCmsFieldMapping = {
  ...previewCtaMapping,
  id: 704,
  variantId: previewUnmappedEntry.variants[0].id,
  tmTextUnitId: 604,
  stringId: 'cms.growth-email-copy.follow-up-email.default.cta_label',
  sourceContent: 'Keep going',
  sourceComment: 'Follow-up action after signup.',
};

const previewFollowUpEntry: ApiCmsEntry = {
  ...previewUnmappedEntry,
  status: 'READY',
  variants: [
    {
      ...previewUnmappedEntry.variants[0],
      fieldMappings: [previewFollowUpMapping, previewFollowUpCtaMapping],
    },
  ],
};

const previewCompleteness: ApiCmsEntryCompleteness = {
  entryId: previewBaseEntry.id,
  entryKey: previewBaseEntry.entryKey,
  locales: [
    {
      localeTag: 'en',
      totalFields: 2,
      approvedFields: 2,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 0,
      complete: true,
    },
    {
      localeTag: 'fr-FR',
      totalFields: 2,
      approvedFields: 0,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 2,
      complete: false,
    },
  ],
  fields: [
    {
      fieldId: previewBaseField.id,
      fieldKey: previewBaseField.fieldKey,
      locales: [
        {
          localeTag: 'en',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'fr-FR',
          totalFields: 1,
          approvedFields: 0,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 1,
          complete: false,
        },
      ],
    },
    {
      fieldId: previewCtaField.id,
      fieldKey: previewCtaField.fieldKey,
      locales: [
        {
          localeTag: 'en',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'fr-FR',
          totalFields: 1,
          approvedFields: 0,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 1,
          complete: false,
        },
      ],
    },
  ],
};

const previewSourceOnlyCompleteness: ApiCmsEntryCompleteness = {
  ...previewCompleteness,
  locales: previewCompleteness.locales.filter((locale) => locale.localeTag === 'en'),
  fields: previewCompleteness.fields?.map((field) => ({
    ...field,
    locales: field.locales.filter((locale) => locale.localeTag === 'en'),
  })),
};

const previewMultipleEditableAlternateCompleteness: ApiCmsEntryCompleteness = {
  ...previewCompleteness,
  locales: [
    ...previewCompleteness.locales,
    {
      localeTag: 'de-DE',
      totalFields: 2,
      approvedFields: 2,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 0,
      complete: true,
    },
  ],
  fields: previewCompleteness.fields?.map((field) => ({
    ...field,
    locales: [
      ...field.locales,
      {
        localeTag: 'de-DE',
        totalFields: 1,
        approvedFields: 1,
        missingFields: 0,
        reviewNeededFields: 0,
        translationNeededFields: 0,
        complete: true,
      },
    ],
  })),
};

const previewProjectCompleteness: ApiCmsProjectCompleteness = {
  projectId: previewProject.id,
  projectKey: previewProject.projectKey,
  authoringSha256: 'a'.repeat(64),
  publishPackageSha256: 'b'.repeat(64),
  publishPackageByteSize: 512,
  localeTags: ['en', 'fr-FR'],
  locales: previewCompleteness.locales,
  entries: [previewCompleteness],
  complete: false,
  releaseChangeSummary: {
    changes: [],
    hasMore: false,
    actionNeededCount: 0,
  },
};

const previewMultipleEditableAlternateProjectCompleteness: ApiCmsProjectCompleteness = {
  ...previewProjectCompleteness,
  localeTags: ['en', 'fr-FR', 'de-DE'],
  locales: previewMultipleEditableAlternateCompleteness.locales,
  entries: [previewMultipleEditableAlternateCompleteness],
};

const previewSingleFieldCompleteness: ApiCmsEntryCompleteness = {
  ...previewCompleteness,
  locales: previewCompleteness.locales.map((locale) => ({
    ...locale,
    totalFields: 1,
    approvedFields: locale.localeTag === 'en' ? 1 : 0,
    translationNeededFields: locale.localeTag === 'en' ? 0 : 1,
  })),
  fields: previewCompleteness.fields?.slice(0, 1),
};

const previewSingleFieldProjectCompleteness: ApiCmsProjectCompleteness = {
  ...previewProjectCompleteness,
  locales: previewSingleFieldCompleteness.locales,
  entries: [previewSingleFieldCompleteness],
};

const previewReadyCompleteness: ApiCmsEntryCompleteness = {
  ...previewCompleteness,
  locales: previewCompleteness.locales.map((locale) =>
    locale.localeTag === 'fr-FR'
      ? {
          ...locale,
          approvedFields: 2,
          translationNeededFields: 0,
          complete: true,
        }
      : locale,
  ),
  fields: previewCompleteness.fields?.map((field) => ({
    ...field,
    locales: field.locales.map((locale) =>
      locale.localeTag === 'fr-FR'
        ? {
            ...locale,
            approvedFields: 1,
            translationNeededFields: 0,
            complete: true,
          }
        : locale,
    ),
  })),
};

const previewReadyProjectCompleteness: ApiCmsProjectCompleteness = {
  ...previewProjectCompleteness,
  locales: previewReadyCompleteness.locales,
  entries: [previewReadyCompleteness],
  complete: true,
};

const previewFollowUpReadyCompleteness: ApiCmsEntryCompleteness = {
  ...previewReadyCompleteness,
  entryId: previewFollowUpEntry.id,
  entryKey: previewFollowUpEntry.entryKey,
  locales: previewReadyCompleteness.locales.map((locale) => ({
    ...locale,
    totalFields: 1,
    approvedFields: 1,
  })),
  fields: previewReadyCompleteness.fields?.slice(0, 1),
};

const previewFollowUpReadyProjectCompleteness: ApiCmsProjectCompleteness = {
  ...previewReadyProjectCompleteness,
  locales: previewFollowUpReadyCompleteness.locales,
  entries: [previewFollowUpReadyCompleteness],
};

const previewJapaneseBlockedCompleteness: ApiCmsEntryCompleteness = {
  ...previewReadyCompleteness,
  locales: [
    ...previewReadyCompleteness.locales,
    {
      localeTag: 'ja-JP',
      totalFields: 2,
      approvedFields: 1,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 1,
      complete: false,
    },
  ],
  fields: [
    {
      fieldId: previewBaseField.id,
      fieldKey: previewBaseField.fieldKey,
      locales: [
        {
          localeTag: 'en',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'fr-FR',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'ja-JP',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
      ],
    },
    {
      fieldId: previewCtaField.id,
      fieldKey: previewCtaField.fieldKey,
      locales: [
        {
          localeTag: 'en',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'fr-FR',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'ja-JP',
          totalFields: 1,
          approvedFields: 0,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 1,
          complete: false,
        },
      ],
    },
  ],
};

const previewJapaneseBlockedProjectCompleteness: ApiCmsProjectCompleteness = {
  ...previewProjectCompleteness,
  localeTags: ['en', 'fr-FR', 'ja-JP'],
  locales: previewJapaneseBlockedCompleteness.locales,
  entries: [previewJapaneseBlockedCompleteness],
  complete: false,
};

const previewJapaneseBlockedMultipleEditableAlternateCompleteness: ApiCmsEntryCompleteness = {
  ...previewJapaneseBlockedCompleteness,
  locales: [
    ...previewJapaneseBlockedCompleteness.locales,
    {
      localeTag: 'de-DE',
      totalFields: 2,
      approvedFields: 2,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 0,
      complete: true,
    },
  ],
  fields: previewJapaneseBlockedCompleteness.fields?.map((field) => ({
    ...field,
    locales: [
      ...field.locales,
      {
        localeTag: 'de-DE',
        totalFields: 1,
        approvedFields: 1,
        missingFields: 0,
        reviewNeededFields: 0,
        translationNeededFields: 0,
        complete: true,
      },
    ],
  })),
};

const previewJapaneseBlockedMultipleEditableAlternateProjectCompleteness: ApiCmsProjectCompleteness =
  {
    ...previewJapaneseBlockedProjectCompleteness,
    localeTags: ['en', 'fr-FR', 'de-DE', 'ja-JP'],
    locales: previewJapaneseBlockedMultipleEditableAlternateCompleteness.locales,
    entries: [previewJapaneseBlockedMultipleEditableAlternateCompleteness],
  };

const previewSourceCopyBlockedCompleteness: ApiCmsEntryCompleteness = {
  ...previewReadyCompleteness,
  locales: previewReadyCompleteness.locales.map((locale) =>
    locale.localeTag === 'en'
      ? {
          ...locale,
          approvedFields: 1,
          missingFields: 1,
          complete: false,
        }
      : locale,
  ),
  fields: previewReadyCompleteness.fields?.map((field) =>
    field.fieldId === previewCtaField.id
      ? {
          ...field,
          locales: field.locales.map((locale) =>
            locale.localeTag === 'en'
              ? {
                  ...locale,
                  approvedFields: 0,
                  missingFields: 1,
                  complete: false,
                }
              : locale,
          ),
        }
      : field,
  ),
};

const previewSourceCopyBlockedProjectCompleteness: ApiCmsProjectCompleteness = {
  ...previewProjectCompleteness,
  locales: previewSourceCopyBlockedCompleteness.locales,
  entries: [previewSourceCopyBlockedCompleteness],
  complete: false,
};

const previewReleaseSnapshots: ApiCmsPublishSnapshot[] = [
  {
    id: 701,
    projectId: previewProject.id,
    snapshotVersion: 7,
    status: 'PUBLISHED',
    localeTags: ['en', 'fr-FR'],
    publishRequestLocaleTags: [],
    publishRequestAuthoringSha256: 'a'.repeat(64),
    publishRequestPackageSha256: 'b'.repeat(64),
    artifactSha256: 'c'.repeat(64),
    artifactByteSize: 512,
    snapshotSigningKeyId: 'preview-v1',
    snapshotSignature: 'd'.repeat(64),
    artifactSignature: 'e'.repeat(64),
    artifactFilename: 'growth-email-copy.v7.json',
    artifactExportPath: '/delivery/growth-email-copy.v7.json',
    createdByUsername: previewAudit.createdByUsername,
    publishedAt: previewAudit.lastModifiedDate,
  },
];

const previewTextUnit: ApiTextUnit = {
  tmTextUnitId: previewBaseMapping.tmTextUnitId,
  tmTextUnitVariantId: 801,
  tmTextUnitCurrentVariantId: 901,
  localeId: 1001,
  name: previewBaseMapping.stringId,
  source: previewBaseMapping.sourceContent,
  comment: previewBaseMapping.sourceComment,
  target: 'Bienvenue chez Acme. Demarrez votre premier projet en quelques minutes.',
  targetLocale: 'fr-FR',
  used: true,
  repositoryName: previewProject.repository.name,
  status: 'TRANSLATION_NEEDED',
  includedInLocalizedFile: true,
};

const previewCtaTextUnit: ApiTextUnit = {
  ...previewTextUnit,
  tmTextUnitId: previewCtaMapping.tmTextUnitId,
  tmTextUnitVariantId: 802,
  tmTextUnitCurrentVariantId: 902,
  name: previewCtaMapping.stringId,
  source: previewCtaMapping.sourceContent,
  comment: previewCtaMapping.sourceComment,
  target: 'Commencer',
};

const previewFollowUpTextUnit: ApiTextUnit = {
  ...previewTextUnit,
  tmTextUnitId: previewFollowUpMapping.tmTextUnitId,
  tmTextUnitVariantId: 805,
  tmTextUnitCurrentVariantId: 905,
  name: previewFollowUpMapping.stringId,
  source: previewFollowUpMapping.sourceContent,
  comment: previewFollowUpMapping.sourceComment,
  target: 'Continuez avec votre prochain projet.',
};

const previewFollowUpCtaTextUnit: ApiTextUnit = {
  ...previewFollowUpTextUnit,
  tmTextUnitId: previewFollowUpCtaMapping.tmTextUnitId,
  tmTextUnitVariantId: 806,
  tmTextUnitCurrentVariantId: 906,
  name: previewFollowUpCtaMapping.stringId,
  source: previewFollowUpCtaMapping.sourceContent,
  comment: previewFollowUpCtaMapping.sourceComment,
  target: 'Continuer',
};

const previewJapaneseTextUnit: ApiTextUnit = {
  ...previewTextUnit,
  tmTextUnitVariantId: 803,
  tmTextUnitCurrentVariantId: 903,
  localeId: 1002,
  target: 'Welcome to Acme. Start your first project in minutes.',
  targetLocale: 'ja-JP',
};

const previewJapaneseCtaTextUnit: ApiTextUnit = {
  ...previewCtaTextUnit,
  tmTextUnitVariantId: 804,
  tmTextUnitCurrentVariantId: 904,
  localeId: 1002,
  target: '',
  targetLocale: 'ja-JP',
};

const previewGermanTextUnit: ApiTextUnit = {
  ...previewTextUnit,
  tmTextUnitVariantId: 807,
  tmTextUnitCurrentVariantId: 907,
  localeId: 1003,
  target: 'Willkommen bei Acme. Starten Sie Ihr erstes Projekt in wenigen Minuten.',
  targetLocale: 'de-DE',
  status: 'APPROVED',
};

const previewGermanCtaTextUnit: ApiTextUnit = {
  ...previewCtaTextUnit,
  tmTextUnitVariantId: 808,
  tmTextUnitCurrentVariantId: 908,
  localeId: 1003,
  target: 'Jetzt starten',
  targetLocale: 'de-DE',
  status: 'APPROVED',
};

const previewUser = {
  username: 'preview-author',
  role: 'ROLE_ADMIN' as const,
  canTranslateAllLocales: true,
  userLocales: ['fr-FR', 'ja-JP'],
};

const previewTargetLocales = [
  { tag: 'fr-FR', label: 'French (France)' },
  { tag: 'ja-JP', label: 'Japanese (Japan)' },
];

const previewMultipleEditableAlternateTargetLocales = [
  { tag: 'fr-FR', label: 'French (France)' },
  { tag: 'de-DE', label: 'German (Germany)' },
  { tag: 'ja-JP', label: 'Japanese (Japan)' },
];

function preventSubmit(event: FormEvent) {
  event.preventDefault();
}

function buildEntryDraft(entry: ApiCmsEntry) {
  return {
    contentTypeId: String(entry.contentTypeId),
    entryKey: entry.entryKey,
    name: entry.name,
    description: entry.description ?? '',
    status: entry.status,
    metadataJson: entry.metadataJson ?? '',
  };
}

function buildFieldDraft(field: ApiCmsContentTypeField) {
  return {
    contentTypeId: String(field.contentTypeId),
    fieldKey: field.fieldKey,
    name: field.name,
    description: field.description ?? '',
    sourceContent: '',
    sourceComment: '',
    fieldType: field.fieldType,
    required: field.required,
    sortOrder: String(field.sortOrder),
  };
}

function isUnavailableReleaseTargetPreviewMode(mode: ContentCmsPreviewMode) {
  return (
    mode === 'release-target-unavailable' ||
    mode === 'release-target-repaired-unavailable' ||
    mode === 'release-target-reconnect-error' ||
    mode === 'release-target-reconnect-conflict' ||
    mode === 'release-target-dirty-source-draft' ||
    mode === 'release-target-dirty-source-conflict'
  );
}

function isRemovedReleaseTargetPreviewMode(mode: ContentCmsPreviewMode) {
  return (
    mode === 'release-target-language-removed' ||
    mode === 'release-target-language-removed-multiple-alternates' ||
    mode === 'release-target-language-add-failed' ||
    mode === 'release-target-language-add-conflict' ||
    mode === 'release-target-language-add-conflict-still-removed' ||
    mode === 'release-target-language-add-conflict-refresh-failed' ||
    mode === 'release-target-language-add-conflict-refresh-failed-retry-still-removed' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored'
  );
}

function isMultipleEditableAlternatePreviewMode(mode: ContentCmsPreviewMode) {
  return (
    mode === 'translation-language-removed-multiple-alternates' ||
    mode === 'release-target-language-removed-multiple-alternates' ||
    mode === 'release-target-language-add-failed' ||
    mode === 'release-target-language-add-conflict' ||
    mode === 'release-target-language-add-conflict-still-removed' ||
    mode === 'release-target-language-add-conflict-refresh-failed' ||
    mode === 'release-target-language-add-conflict-refresh-failed-retry-still-removed' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored'
  );
}

function isConflictingReleaseTargetAddPreviewMode(mode: ContentCmsPreviewMode) {
  return (
    mode === 'release-target-language-add-conflict' ||
    mode === 'release-target-language-add-conflict-still-removed' ||
    mode === 'release-target-language-add-conflict-refresh-failed' ||
    mode === 'release-target-language-add-conflict-refresh-failed-retry-still-removed' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
    mode ===
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored'
  );
}

function getPreviewTranslations(mode: ContentCmsPreviewMode) {
  if (mode === 'stale-recovery') {
    return [];
  }
  const translationLoadError = mode === 'translation-load-error';
  const textUnit =
    mode === 'missing-translation'
      ? {
          ...previewTextUnit,
          target: null,
          status: null,
          includedInLocalizedFile: false,
        }
      : previewTextUnit;
  const ctaTextUnit = isUnavailableReleaseTargetPreviewMode(mode) ? null : previewCtaTextUnit;
  const translations = [
    ['fr-FR', previewBaseMapping, textUnit],
    ['fr-FR', previewCtaMapping, ctaTextUnit],
    ['fr-FR', previewFollowUpMapping, previewFollowUpTextUnit],
    ['fr-FR', previewFollowUpCtaMapping, previewFollowUpCtaTextUnit],
    ['ja-JP', previewBaseMapping, previewJapaneseTextUnit],
    ['ja-JP', previewCtaMapping, previewJapaneseCtaTextUnit],
  ] as const;
  const availableTranslations = translationLoadError
    ? translations.filter(
        ([localeTag, mapping]) => localeTag !== 'fr-FR' || mapping.id !== previewBaseMapping.id,
      )
    : translations;
  return isMultipleEditableAlternatePreviewMode(mode)
    ? ([
        ...availableTranslations,
        ['de-DE', previewBaseMapping, previewGermanTextUnit],
        ['de-DE', previewCtaMapping, previewGermanCtaTextUnit],
      ] as const)
    : availableTranslations;
}

function getPreviewTranslationResponse(
  mode: ContentCmsPreviewMode,
  input: RequestInfo | URL,
): Response | null {
  const requestUrl =
    typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
  const pathname = new URL(requestUrl, window.location.origin).pathname;
  const translationRequest = pathname.match(
    /^\/api\/content-cms\/field-mappings\/(\d+)\/translations\/(.+)$/,
  );
  if (translationRequest == null) {
    return null;
  }
  const mappingId = Number(translationRequest[1]);
  const localeTag = decodeURIComponent(translationRequest[2]);
  if (
    mode === 'translation-load-error' &&
    mappingId === previewBaseMapping.id &&
    localeTag === 'fr-FR'
  ) {
    return new Response(null, { status: 500 });
  }
  const previewTranslation = getPreviewTranslations(mode).find(
    ([candidateLocaleTag, mapping]) => candidateLocaleTag === localeTag && mapping.id === mappingId,
  )?.[2];
  if (previewTranslation === undefined) {
    return null;
  }
  if (previewTranslation == null) {
    return new Response(null, { status: 404 });
  }
  return new Response(JSON.stringify(previewTranslation), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function buildPreviewQueryClient(mode: ContentCmsPreviewMode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });
  if (mode === 'translation-load-error') {
    const translationLoadError = new Error('Preview translation could not load.');
    const translationLoadErrorQueryKey = [
      'content-cms-inline-translation',
      previewBaseMapping.id,
      previewBaseMapping.tmTextUnitId,
      'fr-FR',
    ] as const;
    queryClient.setQueryDefaults(translationLoadErrorQueryKey, {
      retryOnMount: false,
    });
    queryClient.getQueryCache().build(
      queryClient,
      {
        queryKey: translationLoadErrorQueryKey,
      },
      {
        data: undefined,
        dataUpdateCount: 0,
        dataUpdatedAt: 0,
        error: translationLoadError,
        errorUpdateCount: 1,
        errorUpdatedAt: Date.now(),
        fetchFailureCount: 1,
        fetchFailureReason: translationLoadError,
        fetchMeta: null,
        isInvalidated: false,
        status: 'error',
        fetchStatus: 'idle',
      },
    );
  }
  getPreviewTranslations(mode).forEach(([localeTag, mapping, previewTranslation]) => {
    queryClient.setQueryData(
      ['content-cms-inline-translation', mapping.id, mapping.tmTextUnitId, localeTag],
      previewTranslation,
    );
  });
  return queryClient;
}

function buildPreviewWorkspaceProps(
  mode: ContentCmsPreviewMode,
  selectedEntryId: string | null = null,
): PreviewWorkspaceProps {
  const sourceOnlyRequiredSourceEmpty = mode === 'source-only-required-source-empty';
  const sourceOnly = mode === 'source-only' || sourceOnlyRequiredSourceEmpty;
  const blankFieldPlacement = mode === 'blank-field-placement';
  const blankItemPlacement = mode === 'blank-item-placement';
  const blankItemName = mode === 'blank-item-name';
  const emptyContentItems = mode === 'empty-content-items';
  const multiItemNoSource = mode === 'multi-item-no-source';
  const newItemNoFields = mode === 'new-item-no-fields';
  const requiredSourceEmpty = mode === 'required-source-empty' || sourceOnlyRequiredSourceEmpty;
  const releaseLoading = mode === 'release-loading';
  const translationRefreshFailed = mode === 'translation-refresh-failed';
  const translationEditorUnavailable = mode === 'translation-editor-unavailable';
  const translationLanguageRemoved = mode === 'translation-language-removed';
  const translationLanguageRemovedMultipleAlternates =
    mode === 'translation-language-removed-multiple-alternates';
  const translationLanguageRemovedNoAccess = mode === 'translation-language-removed-no-access';
  const translationLanguageRemovedNoTargets = mode === 'translation-language-removed-no-targets';
  const translationLanguageRemovedLoading = mode === 'translation-language-removed-loading';
  const translationLanguageRemovedUnsupported = mode === 'translation-language-removed-unsupported';
  const translationLocaleNoAccess = mode === 'translation-locale-no-access';
  const staleRecovery = mode === 'stale-recovery';
  const optionalWait = mode === 'optional-wait' || mode === 'optional-source-draft';
  const optionalSourceDraft = mode === 'optional-source-draft';
  const savedContentRepair = mode === 'saved-content-repair';
  const readyForRelease = mode === 'ready-for-release';
  const releaseStart = mode === 'release-start';
  const releaseChanged = mode === 'release-changed';
  const releaseChangedMultiItem = mode === 'release-changed-multi-item';
  const releaseChangedItemReview = mode === 'release-changed-item-review';
  const releaseOverflow = mode === 'release-overflow';
  const releaseChecking = mode === 'release-checking';
  const releaseConfirmation = mode === 'release-confirmation';
  const releasePublishing = mode === 'release-publishing';
  const releaseSuccess = mode === 'release-success';
  const releaseRetry = mode === 'release-retry';
  const releaseBlocker = mode === 'release-blocker';
  const releaseLocaleBlocker = mode === 'release-locale-blocker';
  const releaseRepairRefreshFailed = mode === 'release-repair-refresh-failed';
  const releaseSourceBlocker = mode === 'release-source-blocker';
  const releaseSingleBlocker = mode === 'release-single-blocker';
  const releaseTargetUnavailable = isUnavailableReleaseTargetPreviewMode(mode);
  const releaseTargetDirtySourceConflict = mode === 'release-target-dirty-source-conflict';
  const releaseTargetDirtySourceDraft =
    mode === 'release-target-dirty-source-draft' || releaseTargetDirtySourceConflict;
  const releaseTargetLanguageRemoved = isRemovedReleaseTargetPreviewMode(mode);
  const releaseTargetLanguageAddFailed = mode === 'release-target-language-add-failed';
  const releaseTargetLanguageAddConflict = isConflictingReleaseTargetAddPreviewMode(mode);
  const releaseTargetLanguageRemovedMultipleAlternates =
    mode === 'release-target-language-removed-multiple-alternates' ||
    releaseTargetLanguageAddFailed ||
    releaseTargetLanguageAddConflict;
  const selectedFollowUpEntry =
    releaseChangedItemReview ||
    (releaseChangedMultiItem && selectedEntryId === String(previewFollowUpEntry.id));
  const selectedField = staleRecovery
    ? {
        ...previewBaseField,
        entityVersion: previewBaseField.entityVersion + 1,
        description: 'Hero headline from another editor',
      }
    : blankFieldPlacement
      ? {
          ...previewBaseField,
          description: '',
        }
      : previewBaseField;
  const selectedMapping = selectedFollowUpEntry
    ? previewFollowUpMapping
    : requiredSourceEmpty
      ? null
      : staleRecovery
        ? {
            ...previewBaseMapping,
            entityVersion: previewBaseMapping.entityVersion + 1,
            sourceContent: 'Hello from another editor',
            sourceComment: 'Hero headline from another editor',
          }
        : previewBaseMapping;
  const selectedCtaField = releaseTargetDirtySourceConflict
    ? {
        ...previewCtaField,
        entityVersion: previewCtaField.entityVersion + 1,
        description: 'Primary action from another editor',
      }
    : optionalWait
      ? {
          ...previewCtaField,
          required: false,
        }
      : previewCtaField;
  const selectedCtaMapping = selectedFollowUpEntry ? previewFollowUpCtaMapping : previewCtaMapping;
  const selectedFieldMappings = (
    requiredSourceEmpty
      ? [selectedCtaMapping]
      : releaseChangedItemReview || releaseSingleBlocker
        ? [selectedMapping]
        : optionalWait
          ? [selectedMapping]
          : [selectedMapping, selectedCtaMapping]
  ).filter((mapping): mapping is ApiCmsFieldMapping => mapping != null);
  const selectedVariant = savedContentRepair
    ? null
    : selectedFollowUpEntry
      ? previewFollowUpEntry.variants[0]
      : {
          ...previewBaseVariant,
          fieldMappings: selectedFieldMappings,
        };
  const releaseChangedWelcomeEntry: ApiCmsEntry = {
    ...previewBaseEntry,
    status: 'READY',
  };
  const selectedEntry = selectedFollowUpEntry
    ? previewFollowUpEntry
    : staleRecovery
      ? {
          ...previewBaseEntry,
          entityVersion: previewBaseEntry.entityVersion + 1,
          name: 'Welcome email from another editor',
          description: 'Signup confirmation from another editor',
          variants: selectedVariant == null ? [] : [selectedVariant],
        }
      : {
          ...previewBaseEntry,
          description: blankItemPlacement ? null : previewBaseEntry.description,
          status:
            releaseStart ||
            releaseChanged ||
            releaseChangedMultiItem ||
            releaseChangedItemReview ||
            releaseOverflow ||
            releaseChecking ||
            releaseConfirmation ||
            releasePublishing ||
            releaseSuccess ||
            releaseRetry ||
            releaseBlocker ||
            releaseLocaleBlocker ||
            releaseRepairRefreshFailed ||
            releaseSourceBlocker ||
            releaseSingleBlocker ||
            releaseTargetUnavailable ||
            releaseTargetLanguageRemoved
              ? 'READY'
              : previewBaseEntry.status,
          variants: selectedVariant == null ? [] : [selectedVariant],
        };
  const entryDraft = staleRecovery
    ? {
        ...buildEntryDraft(selectedEntry),
        name: 'Welcome email draft',
        description: 'Shown after signup',
      }
    : blankItemName
      ? {
          ...buildEntryDraft(selectedEntry),
          name: '',
        }
      : buildEntryDraft(selectedEntry);
  const savedEntryDraft = buildEntryDraft(selectedEntry);
  const fieldDraft = staleRecovery
    ? {
        ...buildFieldDraft(selectedField),
        description: 'Hero headline',
      }
    : buildFieldDraft(selectedField);
  const savedFieldDraft = buildFieldDraft(selectedField);
  const mappingDraft =
    selectedVariant == null
      ? initialMappingDraft
      : buildMappingDraft({
          projectKey: previewProject.projectKey,
          entry: selectedEntry,
          variant: selectedVariant,
          field: selectedField,
          mapping: selectedMapping,
        });
  const ctaMappingDraft =
    selectedVariant == null
      ? initialMappingDraft
      : buildMappingDraft({
          projectKey: previewProject.projectKey,
          entry: selectedEntry,
          variant: selectedVariant,
          field: selectedCtaField,
          mapping: releaseChangedItemReview || optionalWait ? null : selectedCtaMapping,
        });
  const dirtyCtaSourceDraft = optionalSourceDraft || releaseTargetDirtySourceDraft;
  const dirtyCtaMappingDraft: MappingDraft = dirtyCtaSourceDraft
    ? {
        ...ctaMappingDraft,
        sourceContent: optionalSourceDraft ? 'Start free' : 'Start now revised',
      }
    : ctaMappingDraft;
  const ctaFieldDraft = releaseTargetDirtySourceConflict
    ? {
        ...buildFieldDraft(selectedCtaField),
        description: 'Primary action below hero',
      }
    : buildFieldDraft(selectedCtaField);
  const savedCtaFieldDraft = buildFieldDraft(selectedCtaField);
  const dirtyMappingDraft: MappingDraft = staleRecovery
    ? {
        ...mappingDraft,
        sourceContent: 'Hello updated',
        sourceComment: 'Hero headline updated',
      }
    : mappingDraft;
  const authoringFieldDrafts: Record<string, AuthoringFieldDraftState> = {
    [String(selectedField.id)]: {
      draft: fieldDraft,
      dirty: staleRecovery,
      expectedVersion: selectedField.entityVersion,
      savedDraft: savedFieldDraft,
      staleSavedDraft: staleRecovery ? savedFieldDraft : null,
    },
    [String(selectedCtaField.id)]: {
      draft: ctaFieldDraft,
      dirty: releaseTargetDirtySourceConflict,
      expectedVersion: selectedCtaField.entityVersion,
      savedDraft: savedCtaFieldDraft,
      staleSavedDraft: releaseTargetDirtySourceConflict ? savedCtaFieldDraft : null,
    },
  };
  const authoringMappingDrafts: Record<string, AuthoringMappingDraftState> =
    selectedVariant == null
      ? {}
      : {
          [String(selectedField.id)]: {
            draft: dirtyMappingDraft,
            dirty: staleRecovery,
            expectedVersion: selectedMapping?.entityVersion ?? null,
            savedDraft: mappingDraft,
            staleSavedDraft: staleRecovery ? mappingDraft : null,
          },
          [String(selectedCtaField.id)]: {
            draft: dirtyCtaMappingDraft,
            dirty: dirtyCtaSourceDraft,
            expectedVersion:
              releaseChangedItemReview || optionalWait ? null : selectedCtaMapping.entityVersion,
            savedDraft: ctaMappingDraft,
            staleSavedDraft: null,
          },
        };
  const authoringRecovery: AuthoringRecovery | null = releaseTargetDirtySourceConflict
    ? {
        kind: 'field-context',
        fieldId: String(selectedCtaField.id),
        message:
          'Placement details changed while you were editing. Current copy is refreshing; review and save again.',
      }
    : staleRecovery
      ? {
          kind: 'field-context',
          fieldId: String(selectedField.id),
          message:
            'Copy context changed while you were editing. Current copy is refreshing; review and save again.',
        }
      : null;

  return {
    projectId: previewProject.id,
    projectName: previewProject.name,
    projectKey: previewProject.projectKey,
    sourceLocale: 'en',
    entries: emptyContentItems
      ? []
      : releaseChangedMultiItem
        ? [releaseChangedWelcomeEntry, previewFollowUpEntry]
        : multiItemNoSource
          ? [selectedEntry, previewUnmappedEntry]
          : [selectedEntry],
    contentTypes: [
      {
        ...previewContentType,
        fields: newItemNoFields
          ? []
          : releaseChangedItemReview || releaseSingleBlocker
            ? [selectedField]
            : [selectedField, selectedCtaField],
      },
    ],
    selectedEntry: emptyContentItems ? null : selectedEntry,
    selectedEntryId: emptyContentItems ? '' : String(selectedEntry.id),
    selectedVariant: emptyContentItems ? null : selectedVariant,
    selectedField: emptyContentItems || savedContentRepair ? null : selectedField,
    selectedEntryCompleteness:
      emptyContentItems || releaseLoading
        ? null
        : sourceOnly ||
            translationLanguageRemovedNoTargets ||
            translationLanguageRemovedLoading ||
            translationLanguageRemovedUnsupported
          ? previewSourceOnlyCompleteness
          : readyForRelease ||
              releaseChanged ||
              releaseChangedMultiItem ||
              releaseChangedItemReview ||
              releaseOverflow ||
              releaseSuccess ||
              releaseRetry
            ? releaseChangedItemReview
              ? previewFollowUpReadyCompleteness
              : previewReadyCompleteness
            : releaseSingleBlocker
              ? previewSingleFieldCompleteness
              : translationLocaleNoAccess || releaseLocaleBlocker || releaseRepairRefreshFailed
                ? previewJapaneseBlockedCompleteness
                : releaseSourceBlocker
                  ? previewSourceCopyBlockedCompleteness
                  : translationLanguageRemovedMultipleAlternates ||
                      releaseTargetLanguageRemovedMultipleAlternates
                    ? previewMultipleEditableAlternateCompleteness
                    : previewCompleteness,
    selectedEntryCompletenessLoading: releaseLoading,
    selectedEntryCompletenessRefreshing: false,
    selectedEntryCompletenessError: translationRefreshFailed || translationEditorUnavailable,
    targetLocaleOptions: translationLanguageRemovedLoading
      ? []
      : translationLanguageRemovedUnsupported
        ? previewTargetLocales.filter((locale) => locale.tag !== 'ja-JP')
        : isMultipleEditableAlternatePreviewMode(mode)
          ? previewMultipleEditableAlternateTargetLocales
          : previewTargetLocales,
    targetLocaleTagsDraft: [],
    targetLocalesLoading: translationLanguageRemovedLoading,
    targetLocalesError: false,
    targetLocalesSaving: false,
    newEntryDraft: initialEntryDraft,
    newEntrySourceDrafts: {},
    entryDraft,
    staleSavedEntryDraft: staleRecovery ? savedEntryDraft : null,
    mappingDraft: dirtyMappingDraft,
    authoringFieldDrafts,
    authoringMappingDrafts,
    authoringRecovery,
    entryContextDirty: staleRecovery || blankItemName,
    hasDirtyFieldContextDrafts: staleRecovery,
    hasDirtySourceCopyDrafts: staleRecovery,
    hasDirtyInlineTranslation: false,
    disabled: false,
    reviewEntryRequestKey:
      translationEditorUnavailable ||
      translationLanguageRemoved ||
      translationLanguageRemovedMultipleAlternates ||
      translationLanguageRemovedNoAccess ||
      translationLanguageRemovedNoTargets ||
      translationLanguageRemovedLoading ||
      translationLanguageRemovedUnsupported ||
      translationLocaleNoAccess
        ? 1
        : 0,
    reviewEntryId:
      translationEditorUnavailable ||
      translationLanguageRemoved ||
      translationLanguageRemovedMultipleAlternates ||
      translationLanguageRemovedNoAccess ||
      translationLanguageRemovedNoTargets ||
      translationLanguageRemovedLoading ||
      translationLanguageRemovedUnsupported ||
      translationLocaleNoAccess
        ? String(selectedEntry.id)
        : null,
    reviewFieldId:
      translationEditorUnavailable ||
      translationLanguageRemoved ||
      translationLanguageRemovedMultipleAlternates ||
      translationLanguageRemovedNoAccess ||
      translationLanguageRemovedNoTargets ||
      translationLanguageRemovedLoading ||
      translationLanguageRemovedUnsupported ||
      translationLocaleNoAccess
        ? String(selectedField.id)
        : null,
    reviewLocaleTag:
      translationLanguageRemoved ||
      translationLanguageRemovedMultipleAlternates ||
      translationLanguageRemovedNoAccess ||
      translationLanguageRemovedNoTargets ||
      translationLanguageRemovedLoading ||
      translationLanguageRemovedUnsupported
        ? 'ja-JP'
        : translationEditorUnavailable || translationLocaleNoAccess
          ? 'fr-FR'
          : null,
    reviewRepairTarget:
      translationEditorUnavailable ||
      translationLanguageRemoved ||
      translationLanguageRemovedMultipleAlternates ||
      translationLanguageRemovedNoAccess ||
      translationLanguageRemovedNoTargets ||
      translationLanguageRemovedLoading ||
      translationLanguageRemovedUnsupported ||
      translationLocaleNoAccess
        ? 'translation'
        : null,
    reviewReason: null,
    reviewReturnMode: null,
    reviewLastReleasedSourceContent: null,
    reviewLastReleasedTranslationContent: null,
    reviewReturnToRelease: false,
    reviewRefreshTranslation: translationEditorUnavailable,
    reviewTranslationRepairSaved: false,
    reviewTranslationReconnectErrorMessage: null,
    reviewTranslationReconnectRefreshFailed: false,
    reviewTranslationReconnectRefreshPending: false,
    sourceCopyConflictRefreshFailed: false,
    sourceCopyConflictRefreshPending: false,
    reviewRequestedLocaleSetupRefreshPending: false,
    onReturnToRelease: () => undefined,
    onEntryChange: () => undefined,
    onNewEntryOpenChange: () => undefined,
    onStartNewEntry: (onReady) => onReady(),
    onCancelNewEntry: (onCancel) => onCancel(),
    onNewEntryDraftChange: () => undefined,
    onNewEntrySourceDraftsChange: () => undefined,
    onNewEntrySubmit: preventSubmit,
    onEntryDraftChange: () => undefined,
    onEntrySubmit: preventSubmit,
    onResetEntryDraft: () => undefined,
    onEntryReleaseStatusChange: () => undefined,
    onFieldFocus: () => true,
    onFieldContextChange: () => undefined,
    onFieldContextSubmit: (_, event) => preventSubmit(event),
    onResetFieldContextDraft: () => undefined,
    onMappingDraftChange: () => undefined,
    onResetMappingDraft: () => undefined,
    onMappingSubmit: (_, event) => preventSubmit(event),
    onSaveAll: () => undefined,
    onInlineTranslationDirtyChange: () => undefined,
    onInlineTranslationReleaseBlockerChange: () => undefined,
    onInlineTranslationSavingChange: () => undefined,
    onInlineTranslationSaved: () => undefined,
    onRequestConfirmation: (_, onConfirm) => onConfirm(),
    onRetryEntryCompleteness: () => undefined,
    onRefreshStaleTranslation: () => undefined,
    onRetrySourceCopyConflictRefresh: () => undefined,
    onTargetLocaleTagsChange: () => undefined,
    onAddTargetLocales: preventSubmit,
    onAddRequestedTargetLocale: () => undefined,
    onRetryTargetLocales: () => undefined,
    onReconnectTranslation: () => undefined,
    onOpenAdvancedSetup: () => undefined,
  };
}

function hasDirtyPreviewMappingDrafts(
  authoringMappingDrafts: Record<string, AuthoringMappingDraftState>,
) {
  return Object.values(authoringMappingDrafts).some((draftState) => draftState.dirty);
}

function hasDirtyPreviewFieldDrafts(
  authoringFieldDrafts: Record<string, AuthoringFieldDraftState>,
) {
  return Object.values(authoringFieldDrafts).some((draftState) => draftState.dirty);
}

export function ContentCmsAuthoringPreview() {
  const mode = getContentCmsPreviewMode(window.location.search);
  const releaseTargetLanguageRemovedMultipleAlternates =
    mode === 'release-target-language-removed-multiple-alternates' ||
    mode === 'release-target-language-add-failed' ||
    isConflictingReleaseTargetAddPreviewMode(mode);
  const releaseConfirmation = mode === 'release-confirmation';
  const releaseChangedMultiItem = mode === 'release-changed-multi-item';
  const releaseChangedItemReview = mode === 'release-changed-item-review';
  const releaseOverflow = mode === 'release-overflow';
  const translationLoadError = mode === 'translation-load-error';
  const queryClient = useMemo(() => buildPreviewQueryClient(mode), [mode]);
  useEffect(() => {
    // Keep DEV preview translation refetches deterministic without a backend.
    const previewFetch = window.fetch;
    window.fetch = async (input, init) =>
      getPreviewTranslationResponse(mode, input) ?? previewFetch(input, init);
    return () => {
      window.fetch = previewFetch;
    };
  }, [mode]);
  const initialSelectedWorkspaceProps = useMemo(() => buildPreviewWorkspaceProps(mode), [mode]);
  const [selectedEntryId, setSelectedEntryId] = useState(
    initialSelectedWorkspaceProps.selectedEntryId,
  );
  const initialWorkspaceProps = useMemo(
    () => buildPreviewWorkspaceProps(mode, selectedEntryId),
    [mode, selectedEntryId],
  );
  const [projectDraft, setProjectDraft] = useState(initialProjectDraft);
  const [firstCopyBlockDraft, setFirstCopyBlockDraft] = useState(initialFirstCopyBlockDraft);
  const [entryDraft, setEntryDraft] = useState(initialWorkspaceProps.entryDraft);
  const [savedEntryDraft, setSavedEntryDraft] = useState(
    initialWorkspaceProps.staleSavedEntryDraft ?? initialWorkspaceProps.entryDraft,
  );
  const [entryContextDirty, setEntryContextDirty] = useState(
    initialWorkspaceProps.entryContextDirty,
  );
  const [mobileCopyCollectionsOpen, setMobileCopyCollectionsOpen] = useState(false);
  const [authoringFieldDrafts, setAuthoringFieldDrafts] = useState(
    initialWorkspaceProps.authoringFieldDrafts,
  );
  const [authoringMappingDrafts, setAuthoringMappingDrafts] = useState(
    initialWorkspaceProps.authoringMappingDrafts,
  );
  const [activeFieldId, setActiveFieldId] = useState(
    mode === 'release-repair-refresh-failed'
      ? String(previewCtaField.id)
      : initialWorkspaceProps.selectedField == null
        ? ''
        : String(initialWorkspaceProps.selectedField.id),
  );
  const [hasDirtyInlineTranslation, setHasDirtyInlineTranslation] = useState(false);
  const [pendingInlineTranslationFieldSwitch, setPendingInlineTranslationFieldSwitch] =
    useState<PendingPreviewInlineTranslationFieldSwitch | null>(null);
  const [entryCompletenessRefreshError, setEntryCompletenessRefreshError] = useState(
    mode === 'translation-refresh-failed',
  );
  const [entryCompletenessUnavailable, setEntryCompletenessUnavailable] = useState(
    mode === 'translation-editor-unavailable',
  );
  const [entryCompletenessRefreshing, setEntryCompletenessRefreshing] = useState(false);
  const entryCompletenessRecoveryTimeoutRef = useRef<number | null>(null);
  const targetLocaleConflictRecoveryTimeoutRef = useRef<number | null>(null);
  const [selectedEntryCompleteness, setSelectedEntryCompleteness] = useState(
    initialWorkspaceProps.selectedEntryCompleteness,
  );
  const [targetLocaleTagsDraft, setTargetLocaleTagsDraft] = useState(
    initialWorkspaceProps.targetLocaleTagsDraft,
  );
  const [authoringRecovery, setAuthoringRecovery] = useState(
    initialWorkspaceProps.authoringRecovery,
  );
  const [reviewTarget, setReviewTarget] = useState<PreviewReviewTarget>(() =>
    mode === 'translation-editor-unavailable' ||
    translationLoadError ||
    mode === 'translation-language-removed' ||
    mode === 'translation-language-removed-multiple-alternates' ||
    mode === 'translation-language-removed-no-access' ||
    mode === 'translation-language-removed-no-targets' ||
    mode === 'translation-language-removed-loading' ||
    mode === 'translation-language-removed-unsupported' ||
    mode === 'translation-locale-no-access'
      ? {
          ...initialPreviewReviewTarget,
          requestKey: 1,
          entryId: String(previewBaseEntry.id),
          fieldId: String(previewBaseField.id),
          localeTag:
            mode === 'translation-language-removed' ||
            mode === 'translation-language-removed-multiple-alternates' ||
            mode === 'translation-language-removed-no-access' ||
            mode === 'translation-language-removed-no-targets' ||
            mode === 'translation-language-removed-loading' ||
            mode === 'translation-language-removed-unsupported'
              ? 'ja-JP'
              : 'fr-FR',
          repairTarget: 'translation',
          refreshTranslation: mode === 'translation-editor-unavailable',
        }
      : mode === 'release-repair-refresh-failed'
        ? {
            ...initialPreviewReviewTarget,
            requestKey: 1,
            entryId: String(previewBaseEntry.id),
            fieldId: String(previewCtaField.id),
            localeTag: 'ja-JP',
            repairTarget: 'translation',
            reviewReturnMode: 'repair-saved',
            returnToRelease: true,
          }
        : mode === 'release-target-dirty-source-draft' ||
            mode === 'release-target-dirty-source-conflict'
          ? {
              ...initialPreviewReviewTarget,
              requestKey: 1,
              entryId: String(previewBaseEntry.id),
              fieldId: String(previewCtaField.id),
              localeTag: 'fr-FR',
              repairTarget: 'translation',
              reviewReason: 'French (France) needs translation before release.',
              reviewReturnMode: 'repair',
              returnToRelease: true,
            }
          : releaseChangedItemReview
            ? {
                ...initialPreviewReviewTarget,
                requestKey: 1,
                entryId: String(previewFollowUpEntry.id),
                reviewReason: 'Included in this release.',
                reviewReturnMode: 'review',
                returnToRelease: true,
              }
            : initialPreviewReviewTarget,
  );
  const [returnedReleaseCompleteness, setReturnedReleaseCompleteness] =
    useState<ApiCmsProjectCompleteness | null>(null);
  const [requestedReleaseTargetReadded, setRequestedReleaseTargetReadded] = useState(false);
  const [requestedReleaseTargetAddFailed, setRequestedReleaseTargetAddFailed] = useState(false);
  const [
    requestedReleaseTargetAddConflictRefreshing,
    setRequestedReleaseTargetAddConflictRefreshing,
  ] = useState(false);
  const [
    requestedReleaseTargetAddConflictRefreshFailed,
    setRequestedReleaseTargetAddConflictRefreshFailed,
  ] = useState(false);
  const [
    requestedReleaseTargetAddConflictRetryStillMissing,
    setRequestedReleaseTargetAddConflictRetryStillMissing,
  ] = useState(false);
  const [
    requestedReleaseTargetAddConflictRepeatedRetryStillMissing,
    setRequestedReleaseTargetAddConflictRepeatedRetryStillMissing,
  ] = useState(false);
  const [releaseOverflowExpanded, setReleaseOverflowExpanded] = useState(false);
  useEffect(() => {
    setReturnedReleaseCompleteness(null);
    setRequestedReleaseTargetReadded(false);
    setRequestedReleaseTargetAddFailed(false);
    setRequestedReleaseTargetAddConflictRefreshing(false);
    setRequestedReleaseTargetAddConflictRefreshFailed(false);
    setRequestedReleaseTargetAddConflictRetryStillMissing(false);
    setRequestedReleaseTargetAddConflictRepeatedRetryStillMissing(false);
    if (targetLocaleConflictRecoveryTimeoutRef.current != null) {
      window.clearTimeout(targetLocaleConflictRecoveryTimeoutRef.current);
      targetLocaleConflictRecoveryTimeoutRef.current = null;
    }
    setAuthoringRecovery(initialWorkspaceProps.authoringRecovery);
  }, [initialWorkspaceProps.authoringRecovery, mode]);
  useEffect(
    () => () => {
      if (entryCompletenessRecoveryTimeoutRef.current != null) {
        window.clearTimeout(entryCompletenessRecoveryTimeoutRef.current);
      }
      if (targetLocaleConflictRecoveryTimeoutRef.current != null) {
        window.clearTimeout(targetLocaleConflictRecoveryTimeoutRef.current);
      }
    },
    [],
  );
  const selectPreviewEntry = useCallback(
    (entryId: string) => {
      if (entryId === selectedEntryId) {
        return;
      }
      const nextWorkspaceProps = buildPreviewWorkspaceProps(mode, entryId);
      setSelectedEntryId(entryId);
      setEntryDraft(nextWorkspaceProps.entryDraft);
      setSavedEntryDraft(nextWorkspaceProps.staleSavedEntryDraft ?? nextWorkspaceProps.entryDraft);
      setEntryContextDirty(nextWorkspaceProps.entryContextDirty);
      setAuthoringFieldDrafts(nextWorkspaceProps.authoringFieldDrafts);
      setAuthoringMappingDrafts(nextWorkspaceProps.authoringMappingDrafts);
      setActiveFieldId(
        nextWorkspaceProps.selectedField == null ? '' : String(nextWorkspaceProps.selectedField.id),
      );
      setHasDirtyInlineTranslation(false);
      setPendingInlineTranslationFieldSwitch(null);
      setEntryCompletenessRefreshError(mode === 'translation-refresh-failed');
      setEntryCompletenessUnavailable(mode === 'translation-editor-unavailable');
      setEntryCompletenessRefreshing(false);
      setSelectedEntryCompleteness(nextWorkspaceProps.selectedEntryCompleteness);
      setTargetLocaleTagsDraft(nextWorkspaceProps.targetLocaleTagsDraft);
      setAuthoringRecovery(nextWorkspaceProps.authoringRecovery);
    },
    [mode, selectedEntryId],
  );
  const clearReleaseReviewTarget = useCallback(() => {
    setRequestedReleaseTargetAddConflictRefreshing(false);
    setRequestedReleaseTargetAddConflictRefreshFailed(false);
    setRequestedReleaseTargetAddConflictRetryStillMissing(false);
    setRequestedReleaseTargetAddConflictRepeatedRetryStillMissing(false);
    setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
    setReviewTarget((current) => ({
      ...initialPreviewReviewTarget,
      requestKey: current.requestKey,
    }));
    if (mode === 'release-target-language-removed') {
      setReturnedReleaseCompleteness(
        requestedReleaseTargetReadded
          ? previewJapaneseBlockedProjectCompleteness
          : previewReadyProjectCompleteness,
      );
    }
    if (releaseTargetLanguageRemovedMultipleAlternates) {
      setReturnedReleaseCompleteness(
        requestedReleaseTargetReadded
          ? previewJapaneseBlockedMultipleEditableAlternateProjectCompleteness
          : previewMultipleEditableAlternateProjectCompleteness,
      );
    }
  }, [mode, releaseTargetLanguageRemovedMultipleAlternates, requestedReleaseTargetReadded]);
  const completePendingInlineTranslationFieldSwitch = useCallback(() => {
    if (pendingInlineTranslationFieldSwitch == null) {
      return false;
    }
    setActiveFieldId(pendingInlineTranslationFieldSwitch.targetFieldId);
    queueCopyFieldScroll(
      pendingInlineTranslationFieldSwitch.targetFieldId,
      pendingInlineTranslationFieldSwitch.focusTarget,
    );
    setPendingInlineTranslationFieldSwitch(null);
    return true;
  }, [pendingInlineTranslationFieldSwitch]);
  const retryPreviewEntryCompleteness = useCallback(() => {
    if (entryCompletenessRecoveryTimeoutRef.current != null) {
      window.clearTimeout(entryCompletenessRecoveryTimeoutRef.current);
    }
    if (requestedReleaseTargetAddConflictRefreshFailed) {
      setRequestedReleaseTargetAddConflictRefreshing(true);
    }
    setEntryCompletenessRefreshing(true);
    entryCompletenessRecoveryTimeoutRef.current = window.setTimeout(() => {
      entryCompletenessRecoveryTimeoutRef.current = null;
      setEntryCompletenessRefreshing(false);
      setEntryCompletenessRefreshError(false);
      setEntryCompletenessUnavailable(false);
      if (requestedReleaseTargetAddConflictRefreshFailed) {
        setRequestedReleaseTargetAddConflictRefreshing(false);
        setRequestedReleaseTargetAddConflictRefreshFailed(false);
        if (
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' &&
          requestedReleaseTargetAddConflictRetryStillMissing
        ) {
          setRequestedReleaseTargetAddConflictRetryStillMissing(false);
          setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
          setRequestedReleaseTargetReadded(true);
          setSelectedEntryCompleteness(previewJapaneseBlockedMultipleEditableAlternateCompleteness);
          completePendingInlineTranslationFieldSwitch();
          return;
        }
        if (
          (mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored') &&
          requestedReleaseTargetAddConflictRetryStillMissing
        ) {
          setRequestedReleaseTargetAddConflictRepeatedRetryStillMissing(true);
        }
        if (
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' &&
          requestedReleaseTargetAddConflictRepeatedRetryStillMissing
        ) {
          setRequestedReleaseTargetAddConflictRepeatedRetryStillMissing(false);
          setRequestedReleaseTargetAddConflictRetryStillMissing(false);
          setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
          setRequestedReleaseTargetReadded(true);
          setSelectedEntryCompleteness(previewJapaneseBlockedMultipleEditableAlternateCompleteness);
          completePendingInlineTranslationFieldSwitch();
          return;
        }
        if (
          mode === 'release-target-language-add-conflict-refresh-failed-retry-still-removed' ||
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again' ||
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored'
        ) {
          setRequestedReleaseTargetAddConflictRetryStillMissing(true);
          setAuthoringRecovery(previewTargetLocaleConflictSettledRecovery);
          completePendingInlineTranslationFieldSwitch();
          return;
        }
        setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
        setRequestedReleaseTargetReadded(true);
        setSelectedEntryCompleteness(previewJapaneseBlockedMultipleEditableAlternateCompleteness);
      }
      completePendingInlineTranslationFieldSwitch();
    }, 400);
  }, [
    completePendingInlineTranslationFieldSwitch,
    mode,
    requestedReleaseTargetAddConflictRefreshFailed,
    requestedReleaseTargetAddConflictRepeatedRetryStillMissing,
    requestedReleaseTargetAddConflictRetryStillMissing,
  ]);
  const addPreviewTargetLocales = useCallback(
    (localeTags: string[]) => {
      setTargetLocaleTagsDraft([]);
      setAuthoringRecovery(null);
      setRequestedReleaseTargetAddConflictRefreshing(false);
      setRequestedReleaseTargetAddConflictRefreshFailed(false);
      if (localeTags.includes('ja-JP')) {
        const repeatedConflictAfterStillMissing =
          (mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored') &&
          requestedReleaseTargetAddConflictRetryStillMissing;
        const repeatedConflictRefreshFails =
          (mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
            mode ===
              'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored') &&
          repeatedConflictAfterStillMissing;
        if (
          mode ===
            'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' &&
          requestedReleaseTargetAddConflictRepeatedRetryStillMissing
        ) {
          setRequestedReleaseTargetAddConflictRepeatedRetryStillMissing(false);
          setRequestedReleaseTargetAddConflictRetryStillMissing(false);
          setRequestedReleaseTargetReadded(true);
          setSelectedEntryCompleteness(previewJapaneseBlockedMultipleEditableAlternateCompleteness);
          return;
        }
        if (
          mode === 'release-target-language-add-conflict-refresh-failed-retry-still-removed' &&
          requestedReleaseTargetAddConflictRetryStillMissing
        ) {
          setRequestedReleaseTargetAddConflictRetryStillMissing(false);
          setRequestedReleaseTargetReadded(true);
          setSelectedEntryCompleteness(previewJapaneseBlockedMultipleEditableAlternateCompleteness);
          return;
        }
        if (mode === 'release-target-language-add-failed' && !requestedReleaseTargetAddFailed) {
          setRequestedReleaseTargetAddFailed(true);
          setAuthoringRecovery(previewTargetLocaleSaveFailure);
          return;
        }
        if (isConflictingReleaseTargetAddPreviewMode(mode)) {
          setRequestedReleaseTargetAddConflictRefreshing(true);
          setAuthoringRecovery(previewTargetLocaleConflictRecovery);
          if (targetLocaleConflictRecoveryTimeoutRef.current != null) {
            window.clearTimeout(targetLocaleConflictRecoveryTimeoutRef.current);
          }
          targetLocaleConflictRecoveryTimeoutRef.current = window.setTimeout(() => {
            targetLocaleConflictRecoveryTimeoutRef.current = null;
            setRequestedReleaseTargetAddConflictRefreshing(false);
            if (
              mode === 'release-target-language-add-conflict-still-removed' ||
              (repeatedConflictAfterStillMissing && !repeatedConflictRefreshFails)
            ) {
              if (repeatedConflictAfterStillMissing) {
                setRequestedReleaseTargetAddConflictRetryStillMissing(true);
              }
              setAuthoringRecovery(previewTargetLocaleConflictSettledRecovery);
              return;
            }
            if (
              mode === 'release-target-language-add-conflict-refresh-failed' ||
              mode === 'release-target-language-add-conflict-refresh-failed-retry-still-removed' ||
              mode ===
                'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again' ||
              mode ===
                'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed' ||
              mode ===
                'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored' ||
              mode ===
                'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored' ||
              mode ===
                'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored'
            ) {
              setRequestedReleaseTargetAddConflictRefreshFailed(true);
              setAuthoringRecovery(previewTargetLocaleConflictRefreshFailedRecovery);
              return;
            }
            setAuthoringRecovery(null);
            setRequestedReleaseTargetReadded(true);
            setSelectedEntryCompleteness(
              previewJapaneseBlockedMultipleEditableAlternateCompleteness,
            );
          }, 400);
          return;
        }
        setRequestedReleaseTargetReadded(isRemovedReleaseTargetPreviewMode(mode));
        setSelectedEntryCompleteness(
          isMultipleEditableAlternatePreviewMode(mode)
            ? previewJapaneseBlockedMultipleEditableAlternateCompleteness
            : previewJapaneseBlockedCompleteness,
        );
        return;
      }
      if (localeTags.includes('fr-FR')) {
        setSelectedEntryCompleteness(previewCompleteness);
      }
    },
    [
      mode,
      requestedReleaseTargetAddConflictRepeatedRetryStillMissing,
      requestedReleaseTargetAddConflictRetryStillMissing,
      requestedReleaseTargetAddFailed,
    ],
  );
  const updatePreviewTargetLocaleTags = useCallback((localeTags: string[]) => {
    setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
    setTargetLocaleTagsDraft(localeTags);
  }, []);
  const workspaceProps = useMemo(() => {
    const selectedField =
      initialWorkspaceProps.contentTypes
        .flatMap((contentType) => contentType.fields)
        .find((field) => String(field.id) === activeFieldId) ?? initialWorkspaceProps.selectedField;
    const selectedFieldId = selectedField == null ? null : String(selectedField.id);
    const selectedMappingDraftState =
      selectedFieldId == null ? null : authoringMappingDrafts[selectedFieldId];
    const hasDirtyFieldContextDrafts = hasDirtyPreviewFieldDrafts(authoringFieldDrafts);
    const hasDirtySourceCopyDrafts = hasDirtyPreviewMappingDrafts(authoringMappingDrafts);
    const entryCompletenessError = entryCompletenessRefreshError || entryCompletenessUnavailable;

    return {
      ...initialWorkspaceProps,
      entryDraft,
      entryContextDirty,
      selectedField,
      mappingDraft: selectedMappingDraftState?.draft ?? initialWorkspaceProps.mappingDraft,
      authoringFieldDrafts,
      authoringMappingDrafts,
      authoringRecovery,
      hasDirtyFieldContextDrafts,
      hasDirtySourceCopyDrafts,
      hasDirtyInlineTranslation,
      pendingInlineTranslationFieldSwitch,
      selectedEntryCompleteness: entryCompletenessUnavailable ? null : selectedEntryCompleteness,
      selectedEntryCompletenessRefreshing: entryCompletenessRefreshing,
      selectedEntryCompletenessError:
        entryCompletenessError || requestedReleaseTargetAddConflictRefreshFailed,
      targetLocaleTagsDraft,
      targetLocalesSaving:
        initialWorkspaceProps.targetLocalesSaving || requestedReleaseTargetAddConflictRefreshing,
      disabled: initialWorkspaceProps.disabled || requestedReleaseTargetAddConflictRefreshing,
      reviewEntryRequestKey: reviewTarget.requestKey,
      reviewEntryId: reviewTarget.entryId,
      reviewFieldId: reviewTarget.fieldId,
      reviewLocaleTag: reviewTarget.localeTag,
      reviewRepairTarget: reviewTarget.repairTarget,
      reviewReason: reviewTarget.reviewReason,
      reviewReturnMode: reviewTarget.reviewReturnMode,
      reviewLastReleasedSourceContent: reviewTarget.lastReleasedSourceContent,
      reviewLastReleasedTranslationContent: reviewTarget.lastReleasedTranslationContent,
      reviewReturnToRelease: reviewTarget.returnToRelease,
      reviewRefreshTranslation: reviewTarget.refreshTranslation,
      reviewTranslationRepairSaved: reviewTarget.translationRepairSaved,
      reviewTranslationReconnectErrorMessage: reviewTarget.translationReconnectErrorMessage,
      onReturnToRelease: clearReleaseReviewTarget,
      onEntryChange: selectPreviewEntry,
      onEntryDraftChange: (nextDraft: EntryDraft) => {
        setEntryDraft(nextDraft);
        setEntryContextDirty(true);
      },
      onEntrySubmit: (event: FormEvent) => {
        preventSubmit(event);
        setSavedEntryDraft(entryDraft);
        setEntryContextDirty(false);
      },
      onResetEntryDraft: () => {
        setEntryDraft(savedEntryDraft);
        setEntryContextDirty(false);
      },
      onFieldContextChange: (fieldId: string, description: string) => {
        setAuthoringFieldDrafts((current) => {
          const draftState = current[fieldId];
          if (draftState == null) {
            return current;
          }
          return {
            ...current,
            [fieldId]: {
              ...draftState,
              draft: {
                ...draftState.draft,
                description,
              },
              dirty: true,
            },
          };
        });
      },
      onFieldContextSubmit: (fieldId: string, event: FormEvent) => {
        preventSubmit(event);
        setAuthoringFieldDrafts((current) => {
          const draftState = current[fieldId];
          if (draftState == null) {
            return current;
          }
          return {
            ...current,
            [fieldId]: {
              ...draftState,
              dirty: false,
              savedDraft: draftState.draft,
            },
          };
        });
      },
      onResetFieldContextDraft: (fieldId: string) => {
        setAuthoringFieldDrafts((current) => {
          const draftState = current[fieldId];
          if (draftState == null) {
            return current;
          }
          return {
            ...current,
            [fieldId]: {
              ...draftState,
              draft: draftState.savedDraft,
              dirty: false,
            },
          };
        });
      },
      onFieldFocus: (
        fieldId: string,
        options?: {
          cancelPendingFieldSwitch?: boolean;
          focusTarget?: CopyFieldFocusTarget;
        },
      ) => {
        if (fieldId === selectedFieldId) {
          if (options?.cancelPendingFieldSwitch) {
            setPendingInlineTranslationFieldSwitch(null);
          }
          return true;
        }
        if (entryCompletenessRefreshError) {
          const nextField =
            initialWorkspaceProps.contentTypes
              .flatMap((contentType) => contentType.fields)
              .find((field) => String(field.id) === fieldId) ?? null;
          setPendingInlineTranslationFieldSwitch({
            sourceFieldId: selectedFieldId ?? fieldId,
            targetFieldId: fieldId,
            targetFieldName: nextField?.name ?? 'another field',
            focusTarget: options?.focusTarget ?? 'field',
            reason: 'refresh-error',
          });
          return false;
        }
        if (hasDirtyInlineTranslation) {
          const nextField =
            initialWorkspaceProps.contentTypes
              .flatMap((contentType) => contentType.fields)
              .find((field) => String(field.id) === fieldId) ?? null;
          setPendingInlineTranslationFieldSwitch({
            sourceFieldId: selectedFieldId ?? fieldId,
            targetFieldId: fieldId,
            targetFieldName: nextField?.name ?? 'another field',
            focusTarget: options?.focusTarget ?? 'field',
            reason: 'dirty-translation',
          });
          return false;
        }
        setActiveFieldId(fieldId);
        setPendingInlineTranslationFieldSwitch(null);
        return true;
      },
      onMappingDraftChange: (fieldId: string, nextDraft: MappingDraft) => {
        setAuthoringMappingDrafts((current) => {
          const draftState = current[fieldId];
          if (draftState == null) {
            return current;
          }
          return {
            ...current,
            [fieldId]: {
              ...draftState,
              draft: nextDraft,
              dirty: true,
            },
          };
        });
      },
      onResetMappingDraft: (fieldId: string) => {
        setAuthoringMappingDrafts((current) => {
          const draftState = current[fieldId];
          if (draftState == null) {
            return current;
          }
          return {
            ...current,
            [fieldId]: {
              ...draftState,
              draft: draftState.savedDraft,
              dirty: false,
            },
          };
        });
      },
      onMappingSubmit: (fieldId: string, event: FormEvent) => {
        preventSubmit(event);
        setAuthoringMappingDrafts((current) => {
          const draftState = current[fieldId];
          if (draftState == null) {
            return current;
          }
          return {
            ...current,
            [fieldId]: {
              ...draftState,
              dirty: false,
              savedDraft: draftState.draft,
            },
          };
        });
      },
      onSaveAll: () => {
        setSavedEntryDraft(entryDraft);
        setEntryContextDirty(false);
        setAuthoringFieldDrafts((current) =>
          Object.fromEntries(
            Object.entries(current).map(([fieldId, draftState]) => [
              fieldId,
              {
                ...draftState,
                dirty: false,
                savedDraft: draftState.draft,
              },
            ]),
          ),
        );
        setAuthoringMappingDrafts((current) =>
          Object.fromEntries(
            Object.entries(current).map(([fieldId, draftState]) => [
              fieldId,
              {
                ...draftState,
                dirty: false,
                savedDraft: draftState.draft,
              },
            ]),
          ),
        );
      },
      onInlineTranslationDirtyChange: (dirty: boolean) => {
        setHasDirtyInlineTranslation(dirty);
        if (!dirty) {
          completePendingInlineTranslationFieldSwitch();
        }
      },
      onInlineTranslationSaved: () => {
        completePendingInlineTranslationFieldSwitch();
      },
      onRetryEntryCompleteness: retryPreviewEntryCompleteness,
      onTargetLocaleTagsChange: updatePreviewTargetLocaleTags,
      onAddTargetLocales: (event: FormEvent) => {
        preventSubmit(event);
        addPreviewTargetLocales(targetLocaleTagsDraft);
      },
      onAddRequestedTargetLocale: (localeTag: string) => {
        addPreviewTargetLocales([localeTag]);
      },
      onReconnectTranslation: (fieldId: string, localeTag: string) => {
        const localeLabel = resolvePreviewLocaleDisplayName(localeTag);
        const reconnectErrorMessage =
          mode === 'release-target-reconnect-error'
            ? `Reconnect ${localeLabel} again. If it keeps failing, ask an admin to check this field.`
            : mode === 'release-target-reconnect-conflict'
              ? `Saved copy changed while reconnecting ${localeLabel}. Review the refreshed source copy, then reconnect ${localeLabel} again.`
              : null;
        if (mode === 'release-target-reconnect-conflict') {
          setAuthoringMappingDrafts((current) => {
            const draftState = current[fieldId];
            if (draftState == null) {
              return current;
            }
            const refreshedDraft = {
              ...draftState.savedDraft,
              sourceContent: 'Start now refreshed',
              sourceComment: 'Primary action label refreshed',
            };
            return {
              ...current,
              [fieldId]: {
                ...draftState,
                draft: refreshedDraft,
                dirty: false,
                expectedVersion:
                  draftState.expectedVersion == null ? null : draftState.expectedVersion + 1,
                savedDraft: refreshedDraft,
                staleSavedDraft: null,
              },
            };
          });
        }
        setReviewTarget((current) => ({
          ...current,
          requestKey: reconnectErrorMessage == null ? current.requestKey + 1 : current.requestKey,
          entryId: selectedEntryId,
          fieldId,
          localeTag,
          repairTarget: 'translation',
          refreshTranslation: reconnectErrorMessage == null,
          translationRepairSaved: reconnectErrorMessage == null,
          translationReconnectErrorMessage: reconnectErrorMessage,
        }));
      },
    };
  }, [
    activeFieldId,
    addPreviewTargetLocales,
    authoringFieldDrafts,
    authoringMappingDrafts,
    authoringRecovery,
    clearReleaseReviewTarget,
    completePendingInlineTranslationFieldSwitch,
    entryContextDirty,
    entryDraft,
    entryCompletenessRefreshError,
    entryCompletenessUnavailable,
    entryCompletenessRefreshing,
    hasDirtyInlineTranslation,
    initialWorkspaceProps,
    mode,
    pendingInlineTranslationFieldSwitch,
    reviewTarget,
    requestedReleaseTargetAddConflictRefreshing,
    requestedReleaseTargetAddConflictRefreshFailed,
    retryPreviewEntryCompleteness,
    savedEntryDraft,
    selectedEntryId,
    selectedEntryCompleteness,
    selectPreviewEntry,
    targetLocaleTagsDraft,
    updatePreviewTargetLocaleTags,
  ]);
  const hasProjectScopedNonTranslationDraftChanges =
    workspaceProps.entryContextDirty ||
    workspaceProps.hasDirtyFieldContextDrafts ||
    workspaceProps.hasDirtySourceCopyDrafts;
  const authorReleaseBlockedHint = workspaceProps.hasDirtyInlineTranslation
    ? 'Save translation edits before releasing approved copy.'
    : hasProjectScopedNonTranslationDraftChanges
      ? 'Save visible changes before releasing approved copy.'
      : null;
  const releaseRecovery: ReleaseRecovery | null =
    mode === 'release-retry'
      ? {
          kind: 'retry',
          message:
            'The release request did not finish. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
        }
      : mode === 'release-repair-refresh-failed'
        ? {
            kind: 'repair-refresh',
            message:
              'Your repair saved, but this release could not refresh. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
          }
        : null;
  const releaseHistoryChange: ReleaseHistoryChange | null =
    mode === 'release-changed' ||
    releaseChangedMultiItem ||
    releaseChangedItemReview ||
    releaseOverflow
      ? {
          message: 'Translation or approval status changed since the last release.',
          changeSummary: {
            changes: releaseChangedItemReview
              ? [
                  {
                    kind: 'CONTENT_ITEM_ADDED',
                    entryId: previewFollowUpEntry.id,
                    entryName: previewFollowUpEntry.name,
                  },
                ]
              : releaseChangedMultiItem
                ? [
                    {
                      kind: 'SOURCE_COPY_CHANGED',
                      entryId: previewFollowUpEntry.id,
                      entryName: previewFollowUpEntry.name,
                      fieldId: previewBaseField.id,
                      fieldName: previewBaseField.name,
                      lastReleasedSourceContent: 'Keep building with one more project.',
                    },
                    {
                      kind: 'TRANSLATION_CHANGED',
                      entryId: previewFollowUpEntry.id,
                      entryName: previewFollowUpEntry.name,
                      fieldId: previewBaseField.id,
                      fieldName: previewBaseField.name,
                      localeTag: 'fr-FR',
                      lastReleasedTranslationContent: 'Continuez avec un projet de plus.',
                    },
                    {
                      kind: 'LOCALE_ADDED',
                      localeTag: 'es-ES',
                    },
                    {
                      kind: 'CONTENT_ITEM_ADDED',
                      entryId: previewFollowUpEntry.id,
                      entryName: previewFollowUpEntry.name,
                    },
                    {
                      kind: 'SOURCE_COPY_CHANGED',
                      entryId: previewBaseEntry.id,
                      entryName: 'Welcome email',
                      fieldId: previewBaseField.id,
                      fieldName: 'Headline',
                      lastReleasedSourceContent: 'Welcome to Acme.',
                    },
                    {
                      kind: 'TRANSLATION_CHANGED',
                      entryId: previewBaseEntry.id,
                      entryName: 'Welcome email',
                      fieldId: previewBaseField.id,
                      fieldName: 'Headline',
                      localeTag: 'fr-FR',
                      lastReleasedTranslationContent: 'Bienvenue chez Acme.',
                    },
                  ]
                : [
                    {
                      kind: 'TRANSLATION_CHANGED',
                      entryId: previewBaseEntry.id,
                      entryName: 'Welcome email',
                      fieldId: previewBaseField.id,
                      fieldName: 'Headline',
                      localeTag: 'fr-FR',
                      lastReleasedTranslationContent: 'Bienvenue chez Acme.',
                    },
                    {
                      kind: 'SOURCE_COPY_CHANGED',
                      entryId: previewBaseEntry.id,
                      entryName: 'Welcome email',
                      fieldId: previewBaseField.id,
                      fieldName: 'Headline',
                      lastReleasedSourceContent: 'Welcome to Acme.',
                    },
                    {
                      kind: 'FIELD_ADDED',
                      entryId: previewBaseEntry.id,
                      entryName: 'Welcome email',
                      fieldId: previewCtaField.id,
                      fieldName: 'CTA label',
                    },
                    {
                      kind: 'LOCALE_ADDED',
                      localeTag: 'es-ES',
                    },
                    {
                      kind: 'TRANSLATION_NEEDS_REVIEW',
                      entryId: previewBaseEntry.id,
                      entryName: 'Welcome email',
                      fieldId: previewBaseField.id,
                      fieldName: 'Headline',
                      localeTag: 'de-DE',
                    },
                    ...(releaseOverflow
                      ? [
                          {
                            kind: 'TRANSLATION_NEEDED' as const,
                            entryId: previewBaseEntry.id,
                            entryName: 'Welcome email',
                            fieldId: previewBaseField.id,
                            fieldName: 'Headline',
                            localeTag: 'ja-JP',
                          },
                        ]
                      : []),
                    ...(releaseOverflowExpanded
                      ? [
                          {
                            kind: 'LOCALE_REMOVED' as const,
                            localeTag: 'ko-KR',
                          },
                          {
                            kind: 'FIELD_REMOVED' as const,
                            entryId: previewBaseEntry.id,
                            entryName: 'Welcome email',
                            fieldId: 999,
                            fieldName: 'Legacy subtitle',
                          },
                          {
                            kind: 'CONTENT_ITEM_REMOVED' as const,
                            entryId: 999,
                            entryName: 'Legacy footer',
                          },
                        ]
                      : []),
                  ],
            hasMore: releaseOverflow && !releaseOverflowExpanded,
            actionNeededCount:
              releaseChangedItemReview || releaseChangedMultiItem ? 0 : releaseOverflow ? 2 : 1,
          },
          loadAllChanges: releaseOverflow
            ? () => {
                setReleaseOverflowExpanded(true);
              }
            : undefined,
        }
      : null;
  const activePreviewUser =
    mode === 'translation-language-removed-no-access'
      ? {
          ...previewUser,
          canTranslateAllLocales: false,
          userLocales: [],
        }
      : mode === 'translation-locale-no-access'
        ? {
            ...previewUser,
            canTranslateAllLocales: false,
            userLocales: ['ja-JP'],
          }
        : previewUser;
  const authorReleaseCompleteness =
    returnedReleaseCompleteness ??
    (mode === 'release-start' || mode === 'release-checking'
      ? null
      : mode === 'release-changed' ||
          mode === 'release-changed-multi-item' ||
          mode === 'release-changed-item-review' ||
          mode === 'release-overflow' ||
          mode === 'release-confirmation' ||
          mode === 'release-publishing' ||
          mode === 'release-success' ||
          mode === 'release-retry'
        ? mode === 'release-changed-item-review'
          ? previewFollowUpReadyProjectCompleteness
          : previewReadyProjectCompleteness
        : mode === 'release-locale-blocker' || mode === 'release-repair-refresh-failed'
          ? previewJapaneseBlockedProjectCompleteness
          : mode === 'release-source-blocker'
            ? previewSourceCopyBlockedProjectCompleteness
            : isRemovedReleaseTargetPreviewMode(mode)
              ? releaseTargetLanguageRemovedMultipleAlternates
                ? previewJapaneseBlockedMultipleEditableAlternateProjectCompleteness
                : previewJapaneseBlockedProjectCompleteness
              : mode === 'release-single-blocker'
                ? previewSingleFieldProjectCompleteness
                : previewProjectCompleteness);
  const blockedAuthorReleaseCompleteness = workspaceProps.hasDirtyInlineTranslation
    ? authorReleaseCompleteness
    : null;

  return (
    <MemoryRouter initialEntries={[`/settings/system/content-cms?cmsPreview=${mode}`]}>
      <QueryClientProvider client={queryClient}>
        <UserContext.Provider value={activePreviewUser}>
          <div className="app-shell app-shell--bare">
            <main className="app-shell__main">
              <div className="settings-subpage">
                <SettingsSubpageHeader
                  backTo="/settings/system"
                  backLabel="Back to settings"
                  context="Settings"
                  title="Product copy"
                />
                <div className="settings-page settings-page--wide content-cms-admin-page">
                  <div
                    className={`content-cms-admin-page__layout${
                      mode === 'clean-start' ? ' is-clean-start' : ''
                    }`}
                  >
                    {mode === 'clean-start' ? null : (
                      <aside
                        className={`content-cms-admin-page__sidebar${
                          mobileCopyCollectionsOpen ? ' is-mobile-open' : ''
                        }`}
                        aria-label="Copy collections"
                      >
                        <CopyCollectionsSidebarToggle
                          controlsId={cmsPreviewCopyCollectionsSidebarBodyId}
                          open={mobileCopyCollectionsOpen}
                          onOpenChange={setMobileCopyCollectionsOpen}
                        />
                        <div
                          id={cmsPreviewCopyCollectionsSidebarBodyId}
                          className="content-cms-admin-page__sidebar-body"
                        >
                          <div className="content-cms-admin-page__sidebar-header">
                            <h2>Copy collections</h2>
                          </div>
                          <button
                            type="button"
                            className="content-cms-admin-page__project-row is-active"
                            aria-label="Open copy collection Growth email copy"
                            aria-current="true"
                          >
                            <span className="content-cms-admin-page__project-row-name">
                              {previewProject.name}
                            </span>
                            <span>{previewProject.description}</span>
                          </button>
                          <StartWritingSpaceButton disabled={false} onClick={() => undefined} />
                        </div>
                      </aside>
                    )}
                    <section
                      className="content-cms-admin-page__main"
                      aria-label="Product copy editor"
                    >
                      {mode === 'clean-start' ? (
                        <FirstWritingSpaceForm
                          projectDraft={projectDraft}
                          firstCopyBlockDraft={firstCopyBlockDraft}
                          disabled={false}
                          onProjectDraftChange={setProjectDraft}
                          onFirstCopyBlockDraftChange={setFirstCopyBlockDraft}
                          onSubmit={preventSubmit}
                        />
                      ) : (
                        <>
                          <CmsEntryWorkspace
                            {...workspaceProps}
                            authorReleasePanel={
                              mode === 'release-start' ||
                              mode === 'release-changed' ||
                              mode === 'release-changed-multi-item' ||
                              mode === 'release-changed-item-review' ||
                              mode === 'release-overflow' ||
                              mode === 'release-checking' ||
                              mode === 'release-confirmation' ||
                              mode === 'release-publishing' ||
                              mode === 'release-success' ||
                              mode === 'release-retry' ||
                              mode === 'release-blocker' ||
                              mode === 'release-locale-blocker' ||
                              mode === 'release-repair-refresh-failed' ||
                              mode === 'release-source-blocker' ||
                              mode === 'release-single-blocker' ||
                              isUnavailableReleaseTargetPreviewMode(mode) ||
                              isRemovedReleaseTargetPreviewMode(mode) ? (
                                <section className="content-cms-admin-page__release-card content-cms-admin-page__release-card--author">
                                  <PublishForm
                                    projectName={previewProject.name}
                                    sourceLocale="en"
                                    entries={workspaceProps.entries}
                                    contentTypes={workspaceProps.contentTypes}
                                    selectedEntryId={workspaceProps.selectedEntryId}
                                    selectedFieldId={activeFieldId}
                                    snapshots={previewReleaseSnapshots}
                                    hasMoreSnapshots={false}
                                    isLoadingOlderSnapshots={false}
                                    completenessResult={
                                      workspaceProps.hasDirtyInlineTranslation
                                        ? null
                                        : authorReleaseCompleteness
                                    }
                                    blockedCompletenessResult={blockedAuthorReleaseCompleteness}
                                    publishLocales=""
                                    disabled={
                                      authorReleaseBlockedHint != null ||
                                      mode === 'release-checking' ||
                                      mode === 'release-publishing'
                                    }
                                    compact
                                    authorFacing
                                    showTechnicalDetails={false}
                                    blockedHint={authorReleaseBlockedHint}
                                    releaseRecovery={releaseRecovery}
                                    releaseCheckPending={mode === 'release-checking'}
                                    releasePublishPending={mode === 'release-publishing'}
                                    releaseSuccess={
                                      mode === 'release-success'
                                        ? ({
                                            message: 'Released Welcome email in version 7.',
                                          } satisfies ReleaseSuccess)
                                        : null
                                    }
                                    releaseHistoryChange={releaseHistoryChange}
                                    onPublishLocalesChange={() => undefined}
                                    onCompletenessSubmit={preventSubmit}
                                    onPublish={() => undefined}
                                    onLoadOlderSnapshots={() => undefined}
                                    onReviewBlockedEntry={(
                                      entryId,
                                      fieldId,
                                      localeTag,
                                      repairTarget,
                                      reviewReason,
                                      reviewReturnMode,
                                      lastReleasedSourceContent,
                                      lastReleasedTranslationContent,
                                    ) => {
                                      selectPreviewEntry(entryId);
                                      setReviewTarget((current) => ({
                                        requestKey: current.requestKey + 1,
                                        entryId,
                                        fieldId: fieldId ?? null,
                                        localeTag: localeTag ?? null,
                                        repairTarget: repairTarget ?? null,
                                        reviewReason: reviewReason ?? null,
                                        reviewReturnMode: reviewReturnMode ?? null,
                                        lastReleasedSourceContent:
                                          lastReleasedSourceContent ?? null,
                                        lastReleasedTranslationContent:
                                          lastReleasedTranslationContent ?? null,
                                        returnToRelease: true,
                                        refreshTranslation: false,
                                        translationRepairSaved:
                                          mode === 'release-target-repaired-unavailable',
                                        translationReconnectErrorMessage: null,
                                      }));
                                      if (fieldId != null) {
                                        setActiveFieldId(fieldId);
                                      }
                                    }}
                                  />
                                </section>
                              ) : null
                            }
                          />
                        </>
                      )}
                    </section>
                  </div>
                </div>
              </div>
            </main>
          </div>
          <ConfirmModal
            open={releaseConfirmation}
            title="Release approved copy?"
            body="Release Welcome email from Growth email copy using every translation language?"
            confirmLabel="Release copy"
            cancelLabel="Cancel"
            confirmVariant="primary"
            onCancel={() => undefined}
            onConfirm={() => undefined}
          />
        </UserContext.Provider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}
