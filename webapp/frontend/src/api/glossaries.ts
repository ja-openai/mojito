import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';

const GLOSSARY_SUGGESTION_SEARCH_POLL_TIMEOUT_MS = 120_000;
const DEFAULT_POLL_INTERVAL_MS = 1000;
const MAX_POLL_INTERVAL_MS = 8000;

export type ApiGlossaryRepositoryRef = {
  id: number;
  name: string;
};

export type ApiGlossarySummary = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  description?: string | null;
  enabled: boolean;
  priority: number;
  scopeMode: 'GLOBAL' | 'SELECTED_REPOSITORIES';
  assetPath: string;
  repositoryCount: number;
  backingRepository: ApiGlossaryRepositoryRef;
};

export type ApiGlossaryDetail = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  description?: string | null;
  enabled: boolean;
  priority: number;
  scopeMode: 'GLOBAL' | 'SELECTED_REPOSITORIES';
  backingRepository: ApiGlossaryRepositoryRef;
  assetPath: string;
  localeTags: string[];
  repositories: ApiGlossaryRepositoryRef[];
  excludedRepositories: ApiGlossaryRepositoryRef[];
};

export type ApiGlossaryTermTranslation = {
  localeTag: string;
  target?: string | null;
  targetComment?: string | null;
  status?: string | null;
};

export type ApiGlossaryTermEvidence = {
  id?: number | null;
  evidenceType: 'SCREENSHOT' | 'STRING_USAGE' | 'CODE_REF' | 'NOTE';
  caption?: string | null;
  imageKey?: string | null;
  tmTextUnitId?: number | null;
  cropX?: number | null;
  cropY?: number | null;
  cropWidth?: number | null;
  cropHeight?: number | null;
  sortOrder?: number | null;
};

export type ApiGlossaryTerm = {
  metadataId?: number | null;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  tmTextUnitId: number;
  termKey: string;
  source: string;
  sourceComment?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  caseSensitive: boolean;
  doNotTranslate: boolean;
  termIndexCandidateId?: number | null;
  termIndexExtractedTermId?: number | null;
  termIndexOccurrenceCount?: number | null;
  termIndexRepositoryCount?: number | null;
  translations: ApiGlossaryTermTranslation[];
  evidence: ApiGlossaryTermEvidence[];
};

export type ApiGlossaryTermsResponse = {
  terms: ApiGlossaryTerm[];
  totalCount: number;
  localeTags: string[];
};

export type ApiInflectionProfileStatus =
  | 'APPROVED'
  | 'DISABLED'
  | 'GENERATED'
  | 'REVIEW_NEEDED'
  | (string & {});

export type ApiInflectionProfileDiagnosticSummary = {
  code?: string | null;
  reason?: string | null;
  message?: string | null;
  formKey?: string | null;
  termId?: string | null;
  messageId?: string | null;
  argument?: string | null;
  relatedArgument?: string | null;
  missing: string[];
  span: number[];
};

export type ApiInflectionProfile = {
  id?: number | null;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  glossaryTermMetadataId: number;
  tmTextUnitId: number;
  termId: string;
  source: string;
  localeTag: string;
  schema: string;
  status: ApiInflectionProfileStatus;
  morphologyJson: string;
  formsJson: string;
  diagnosticsJson: string;
  diagnosticSummaries?: ApiInflectionProfileDiagnosticSummary[];
  missingFormKeys?: string[];
  provenanceJson: string;
};

export type ApiInflectionProfilesResponse = {
  profiles: ApiInflectionProfile[];
};

export type ApiInflectionProfilePackExport = {
  profileCount: number;
  content: string;
  filename: string | null;
  pack: unknown;
};

export type ApiImportInflectionProfilesResponse = {
  localeTag: string;
  profileCount: number;
  createdProfileCount: number;
  updatedProfileCount: number;
  profiles: ApiInflectionProfile[];
};

export type ApiCompiledInflectionProfilePackPreview = {
  approvedProfileCount: number;
  skippedProfileCount: number;
  runtimeExport: string | null;
  compositionMode: string | null;
  profileCount: number;
  formCount: number;
  content: string;
  pack: unknown;
};

export type ApiInflectionBindingStatus =
  | 'ok'
  | 'missing'
  | 'ambiguous'
  | 'unknown'
  | 'unused'
  | 'unsupported-locale-runtime-term-inflection'
  | (string & {});

export type ApiInflectionBindingManifest = {
  schema: 'mojito-mf2-inflection/message-term-binding-manifest/v0';
  locale?: string | null;
  messages: Record<string, string>;
  argumentTerms: Record<string, Record<string, string[]>>;
};

export type ApiInflectionBindingReport = {
  schema: 'mojito-mf2-inflection/term-binding-report/v0';
  locale?: string | null;
  summary: {
    messages: number;
    requiredArguments: number;
    diagnostics: number;
  };
  diagnostics: Array<{
    messageId: string;
    argument: string;
    status: ApiInflectionBindingStatus;
    termIds: string[];
  }>;
  messages: Record<
    string,
    {
      source: string;
      requiredArguments: string[];
      arguments: Record<
        string,
        {
          status: ApiInflectionBindingStatus;
          termIds: string[];
        }
      >;
    }
  >;
};

export type ApiInflectionBindingRenderResponse = {
  locale?: string | null;
  messages: Record<string, string>;
};

export type ApiUpsertInflectionProfileRequest = {
  status: ApiInflectionProfileStatus;
  morphologyJson: string;
  formsJson: string;
  diagnosticsJson: string;
  provenanceJson: string;
};

export type ApiReviewInflectionProfileRequest = {
  status: ApiInflectionProfileStatus;
  morphologyJson?: string | null;
  formsJson?: string | null;
  diagnosticsJson?: string | null;
  provenanceJson?: string | null;
};

export type ApiGlossaryTermSearchField = 'SOURCE' | 'DEFINITION' | 'TARGET' | 'REFERENCES' | 'ALL';

export type ApiGlossaryWorkspaceSummary = {
  totalTerms: number;
  approvedTermCount: number;
  candidateTermCount: number;
  deprecatedTermCount: number;
  rejectedTermCount: number;
  doNotTranslateTermCount: number;
  termsWithEvidenceCount: number;
  termsMissingAnyTranslationCount: number;
  missingTranslationCount: number;
  fullyTranslatedTermCount: number;
  publishReadyTermCount: number;
  truncated: boolean;
};

export type ApiGlossariesResponse = {
  glossaries: ApiGlossarySummary[];
  totalCount: number;
};

export type ApiMatchedGlossaryTerm = {
  glossaryId?: number | null;
  glossaryName?: string | null;
  tmTextUnitId: number;
  termKey?: string | null;
  source: string;
  comment?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  target?: string | null;
  targetComment?: string | null;
  doNotTranslate: boolean;
  caseSensitive: boolean;
  matchType: string;
  startIndex: number;
  endIndex: number;
  matchedText: string;
  evidence: ApiGlossaryTermEvidence[];
};

export type ApiUpsertGlossaryRequest = {
  name: string;
  backingRepositoryName?: string | null;
  description?: string | null;
  enabled?: boolean | null;
  priority?: number | null;
  scopeMode?: 'GLOBAL' | 'SELECTED_REPOSITORIES' | null;
  localeTags?: string[];
  repositoryIds?: number[];
  excludedRepositoryIds?: number[];
};

export type ApiImportGlossaryResponse = {
  createdTermCount: number;
  updatedTermCount: number;
  createdTranslationCount: number;
  updatedTranslationCount: number;
};

export type ApiUpsertGlossaryTermRequest = {
  termKey?: string | null;
  source: string;
  sourceComment?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  caseSensitive?: boolean | null;
  doNotTranslate?: boolean | null;
  replaceTerm?: boolean | null;
  copyTranslationsOnReplace?: boolean | null;
  copyTranslationStatus?: 'KEEP_CURRENT' | 'REVIEW_NEEDED' | 'APPROVED' | null;
  translations?: Array<{
    localeTag: string;
    target?: string | null;
    targetComment?: string | null;
  }>;
  evidence?: Array<{
    evidenceType: 'SCREENSHOT' | 'STRING_USAGE' | 'CODE_REF' | 'NOTE';
    caption?: string | null;
    imageKey?: string | null;
    tmTextUnitId?: number | null;
    cropX?: number | null;
    cropY?: number | null;
    cropWidth?: number | null;
    cropHeight?: number | null;
  }>;
};

export type ApiBatchUpdateGlossaryTermsRequest = {
  tmTextUnitIds: number[];
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  caseSensitive?: boolean | null;
  doNotTranslate?: boolean | null;
};

export type ApiBatchUpdateGlossaryTermsResponse = {
  updatedTermCount: number;
};

export type ApiExtractGlossaryTermsRequest = {
  repositoryIds: number[];
  limit?: number | null;
  minOccurrences?: number | null;
  scanLimit?: number | null;
};

export type ApiExtractedGlossaryCandidate = {
  term: string;
  occurrenceCount: number;
  repositoryCount: number;
  repositories: string[];
  sampleSources: string[];
  suggestedTermType: string;
  suggestedProvenance: string;
  existingInGlossary: boolean;
  confidence: number;
  definition?: string | null;
  rationale?: string | null;
  suggestedPartOfSpeech?: string | null;
  suggestedEnforcement?: string | null;
  suggestedDoNotTranslate: boolean;
  extractionMethod?: string | null;
};

export type ApiExtractGlossaryTermsResponse = {
  candidates: ApiExtractedGlossaryCandidate[];
};

export type ApiGlossaryTermIndexOccurrence = {
  id?: number | null;
  repositoryId?: number | null;
  repositoryName: string;
  assetId?: number | null;
  assetPath?: string | null;
  tmTextUnitId: number;
  textUnitName?: string | null;
  sourceText?: string | null;
  matchedText?: string | null;
  startIndex?: number | null;
  endIndex?: number | null;
  extractionMethod?: string | null;
  confidence?: number | null;
};

export type ApiGlossaryTermIndexCandidateSource = {
  id: number;
  sourceType: string;
  sourceName?: string | null;
  sourceExternalId?: string | null;
  confidence?: number | null;
  metadataJson?: string | null;
  createdDate?: string | null;
};

export type ApiGlossaryTermIndexSuggestionReviewStatus =
  | 'NEW'
  | 'IGNORED'
  | 'ACCEPTED'
  | (string & {});

export type ApiGlossaryTermIndexSuggestionReviewStatusFilter =
  | 'NEW'
  | 'IGNORED'
  | 'ACCEPTED'
  | 'REVIEWED'
  | 'ALL';

export type ApiGlossaryTermIndexSuggestionGlossaryPresence =
  | 'LINKED'
  | 'EXISTING_TERM'
  | 'NOT_IN_GLOSSARY'
  | (string & {});

export type ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter =
  | 'ALL'
  | 'IN_GLOSSARY'
  | 'NOT_IN_GLOSSARY';

export type ApiGlossaryTermIndexSuggestion = {
  termIndexCandidateId: number;
  termIndexExtractedTermId?: number | null;
  normalizedKey: string;
  term: string;
  label?: string | null;
  sourceLocaleTag: string;
  occurrenceCount: number;
  repositoryCount: number;
  sourceCount: number;
  extractedTermMatchCount: number;
  confidence: number;
  definition?: string | null;
  rationale?: string | null;
  suggestedTermType: string;
  suggestedProvenance: string;
  suggestedPartOfSpeech?: string | null;
  suggestedEnforcement: string;
  suggestedDoNotTranslate: boolean;
  examples: ApiGlossaryTermIndexOccurrence[];
  sources: ApiGlossaryTermIndexCandidateSource[];
  lastSignalAt?: string | null;
  candidateReviewStatus: ApiTermIndexLayerReviewStatus;
  candidateReviewAuthority: ApiTermIndexLayerReviewAuthority;
  candidateReviewReason?: string | null;
  candidateReviewRationale?: string | null;
  candidateReviewConfidence?: number | null;
  candidateReviewChangedAt?: string | null;
  candidateReviewChangedByUserId?: number | null;
  candidateReviewChangedByUsername?: string | null;
  candidateReviewChangedByCommonName?: string | null;
  reviewStatus: ApiGlossaryTermIndexSuggestionReviewStatus;
  glossaryPresence: ApiGlossaryTermIndexSuggestionGlossaryPresence;
  selectionMethod: string;
};

export type ApiTermIndexLayerReviewStatus = 'TO_REVIEW' | 'ACCEPTED' | 'REJECTED' | (string & {});

export type ApiTermIndexLayerReviewAuthority = 'NONE' | 'AI' | 'HUMAN' | (string & {});

export type ApiSeedGlossaryTermIndexCandidatesRequest = {
  candidates: Array<{
    term: string;
    sourceLocaleTag?: string | null;
    sourceType?: string | null;
    sourceName?: string | null;
    sourceExternalId?: string | null;
    confidence?: number | null;
    label?: string | null;
    definition?: string | null;
    rationale?: string | null;
    termType?: string | null;
    partOfSpeech?: string | null;
    enforcement?: string | null;
    doNotTranslate?: boolean | null;
    reviewStatus?: ApiTermIndexLayerReviewStatus | null;
    reviewAuthority?: ApiTermIndexLayerReviewAuthority | null;
    reviewReason?: string | null;
    reviewRationale?: string | null;
    reviewConfidence?: number | null;
    metadata?: Record<string, unknown> | null;
  }>;
};

export type ApiSeedGlossaryTermIndexCandidatesResponse = {
  candidateCount: number;
  createdCandidateCount: number;
  updatedCandidateCount: number;
  candidates: Array<{
    termIndexCandidateId: number;
    termIndexExtractedTermId?: number | null;
    term: string;
    normalizedKey: string;
    label?: string | null;
    definition?: string | null;
    rationale?: string | null;
    termType?: string | null;
    partOfSpeech?: string | null;
    enforcement?: string | null;
    doNotTranslate?: boolean | null;
    confidence?: number | null;
  }>;
};

export type ApiGenerateGlossaryTermIndexCandidatesRequest = {
  search?: string | null;
  extractionMethod?: string | null;
  minOccurrences?: number | null;
  limit?: number | null;
};

export type ApiImportGlossaryTermIndexCandidatesRequest = {
  format?: string | null;
  content: string;
};

export type ApiGlossaryTermIndexSuggestionsResponse = {
  suggestions: ApiGlossaryTermIndexSuggestion[];
  totalCount: number;
};

type ApiGlossaryTermIndexSuggestionsHybridResponse = {
  results?: ApiGlossaryTermIndexSuggestionsResponse | null;
  pollingToken?: {
    requestId: string;
    recommendedPollingDurationMillis?: number;
  } | null;
  error?: {
    message?: string;
  } | null;
};

export type ApiAcceptGlossaryTermIndexSuggestionRequest = {
  termKey?: string | null;
  source?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  caseSensitive?: boolean | null;
  doNotTranslate?: boolean | null;
  confidence?: number | null;
  rationale?: string | null;
  evidence?: ApiUpsertGlossaryTermRequest['evidence'];
};

type PollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: string | null;
};

export type ApiPollableTask = {
  id: number;
  isAllFinished: boolean;
  errorMessage?: string | null;
};

export type ApiStartGlossaryExtractionResponse = {
  pollableTask: PollableTaskResponse;
};

export type ApiGlossaryTranslationProposal = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  tmTextUnitId: number;
  source?: string | null;
  localeTag: string;
  proposedTarget: string;
  proposedTargetComment?: string | null;
  note?: string | null;
  status: string;
  reviewerNote?: string | null;
};

export type ApiGlossaryTranslationProposalsResponse = {
  proposals: ApiGlossaryTranslationProposal[];
  totalCount: number;
};

export type ApiMatchGlossaryTermsRequest = {
  repositoryId?: number | null;
  repositoryName?: string | null;
  glossaryName?: string | null;
  localeTag: string;
  sourceText: string;
  excludeTmTextUnitId?: number | null;
};

export type ApiMatchGlossaryTermsResponse = {
  matchedTerms: ApiMatchedGlossaryTerm[];
};

export async function fetchGlossaries(options?: {
  search?: string;
  enabled?: boolean | null;
  limit?: number;
}): Promise<ApiGlossariesResponse> {
  const params = new URLSearchParams();
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  if (typeof options?.enabled === 'boolean') {
    params.set('enabled', String(options.enabled));
  }
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(`/api/glossaries${params.size ? `?${params.toString()}` : ''}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossaries');
  }

  return (await response.json()) as ApiGlossariesResponse;
}

export async function fetchGlossary(glossaryId: number): Promise<ApiGlossaryDetail> {
  const response = await fetch(`/api/glossaries/${glossaryId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary');
  }

  return (await response.json()) as ApiGlossaryDetail;
}

export async function fetchGlossaryTerms(
  glossaryId: number,
  options?: {
    search?: string;
    searchField?: ApiGlossaryTermSearchField;
    localeTags?: string[];
    limit?: number;
  },
): Promise<ApiGlossaryTermsResponse> {
  const params = new URLSearchParams();
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  if (options?.searchField) {
    params.set('searchField', options.searchField);
  }
  options?.localeTags?.forEach((localeTag) => {
    const normalized = localeTag.trim();
    if (normalized) {
      params.append('locale', normalized);
    }
  });
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/glossaries/${glossaryId}/terms${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary terms');
  }

  return (await response.json()) as ApiGlossaryTermsResponse;
}

export async function fetchGlossaryTerm(
  glossaryId: number,
  tmTextUnitId: number,
  options?: {
    localeTags?: string[];
  },
): Promise<ApiGlossaryTerm> {
  const params = new URLSearchParams();
  options?.localeTags?.forEach((localeTag) => {
    const normalized = localeTag.trim();
    if (normalized) {
      params.append('locale', normalized);
    }
  });

  const response = await fetch(
    `/api/glossaries/${glossaryId}/terms/${tmTextUnitId}${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary term');
  }

  return (await response.json()) as ApiGlossaryTerm;
}

export async function fetchInflectionProfiles(
  glossaryId: number,
  localeTag: string,
): Promise<ApiInflectionProfilesResponse> {
  const params = new URLSearchParams({ locale: localeTag });
  const response = await fetch(`/api/glossaries/${glossaryId}/inflection-profiles?${params}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load inflection profiles');
  }

  return (await response.json()) as ApiInflectionProfilesResponse;
}

export async function exportInflectionProfilePack(
  glossaryId: number,
  localeTag: string,
): Promise<ApiInflectionProfilePackExport> {
  const params = new URLSearchParams({ locale: localeTag });
  const response = await fetch(
    `/api/glossaries/${glossaryId}/inflection-profiles/export?${params}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to export inflection profile pack'),
    );
  }

  const content = await response.text().catch(() => '');
  let pack: unknown;
  try {
    pack = JSON.parse(content) as unknown;
  } catch {
    throw new Error('Inflection profile pack response was not valid JSON');
  }

  return {
    ...summarizeAuthoringInflectionPack(pack),
    content,
    filename: parseContentDispositionFilename(response.headers.get('Content-Disposition')),
    pack,
  };
}

export async function importInflectionProfiles(
  glossaryId: number,
  content: string,
): Promise<ApiImportInflectionProfilesResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/inflection-profiles/import`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({ content }),
  });

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to import inflection profiles'),
    );
  }

  return (await response.json()) as ApiImportInflectionProfilesResponse;
}

export async function fetchCompiledInflectionProfilePack(
  glossaryId: number,
  localeTag: string,
): Promise<ApiCompiledInflectionProfilePackPreview> {
  const params = new URLSearchParams({ locale: localeTag });
  const response = await fetch(
    `/api/glossaries/${glossaryId}/inflection-profiles/compiled?${params}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to preview compiled inflection pack'),
    );
  }

  const content = await response.text().catch(() => '');
  let pack: unknown;
  try {
    pack = JSON.parse(content) as unknown;
  } catch {
    throw new Error('Compiled inflection pack response was not valid JSON');
  }

  return {
    approvedProfileCount: parseIntegerHeader(
      response.headers.get('X-Mojito-Inflection-Approved-Profiles'),
    ),
    skippedProfileCount: parseIntegerHeader(
      response.headers.get('X-Mojito-Inflection-Skipped-Profiles'),
    ),
    runtimeExport: parseTextHeader(response.headers.get('X-Mojito-Inflection-Runtime-Export')),
    compositionMode: parseTextHeader(response.headers.get('X-Mojito-Inflection-Composition-Mode')),
    ...summarizeCompiledInflectionPack(pack),
    content,
    pack,
  };
}

async function getGlossaryApiErrorMessage(response: Response, fallbackMessage: string) {
  const text = await response.text().catch(() => '');
  if (!text.trim()) {
    return fallbackMessage;
  }

  try {
    const parsed = JSON.parse(text) as unknown;
    if (isApiErrorRecord(parsed)) {
      const parsedMessage =
        normalizedApiErrorField(parsed.message) ??
        normalizedApiErrorField(parsed.reason) ??
        normalizedApiErrorField(parsed.detail) ??
        normalizedApiErrorField(parsed.error, { allowGenericHttpStatus: false });
      if (parsedMessage) {
        return parsedMessage;
      }
      return fallbackMessage;
    }
  } catch {
    // Fall through to raw text for non-JSON errors.
  }

  return text;
}

function isApiErrorRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function normalizedApiErrorField(
  value: unknown,
  options: { allowGenericHttpStatus?: boolean } = {},
) {
  if (typeof value !== 'string') {
    return null;
  }

  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }

  const quotedReason = trimmed.match(/^(?:\d{3}\s+)?[A-Z_ ]+\s+"(.+)"$/u);
  if (quotedReason?.[1]) {
    return quotedReason[1];
  }

  if (!options.allowGenericHttpStatus && isGenericHttpStatusMessage(trimmed)) {
    return null;
  }

  return trimmed;
}

function isGenericHttpStatusMessage(message: string) {
  return (
    message === 'Bad Request' ||
    message === 'Unauthorized' ||
    message === 'Forbidden' ||
    message === 'Not Found' ||
    message === 'Internal Server Error'
  );
}

export async function reportInflectionBindingManifest(
  glossaryId: number,
  localeTag: string,
  manifest: ApiInflectionBindingManifest,
): Promise<ApiInflectionBindingReport> {
  const params = new URLSearchParams({ locale: localeTag });
  const response = await fetch(
    `/api/glossaries/${glossaryId}/inflection-profiles/bindings/report?${params}`,
    {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        content: JSON.stringify(manifest),
      }),
    },
  );

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to inspect inflection bindings'),
    );
  }

  return (await response.json()) as ApiInflectionBindingReport;
}

export async function renderInflectionBindingManifest(
  glossaryId: number,
  localeTag: string,
  manifest: ApiInflectionBindingManifest,
  variables: Record<string, string> = {},
): Promise<ApiInflectionBindingRenderResponse> {
  const params = new URLSearchParams({ locale: localeTag });
  const response = await fetch(
    `/api/glossaries/${glossaryId}/inflection-profiles/bindings/render?${params}`,
    {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        content: JSON.stringify(manifest),
        variables,
      }),
    },
  );

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to render inflection bindings'),
    );
  }

  return (await response.json()) as ApiInflectionBindingRenderResponse;
}

export async function reviewInflectionProfile(
  glossaryId: number,
  tmTextUnitId: number,
  localeTag: string,
  request: ApiReviewInflectionProfileRequest,
): Promise<ApiInflectionProfile> {
  const response = await fetch(
    `/api/glossaries/${glossaryId}/terms/${tmTextUnitId}/inflection-profiles/${encodeURIComponent(
      localeTag,
    )}/review`,
    {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(request),
    },
  );

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to review inflection profile'),
    );
  }

  return (await response.json()) as ApiInflectionProfile;
}

export async function upsertInflectionProfile(
  glossaryId: number,
  tmTextUnitId: number,
  localeTag: string,
  request: ApiUpsertInflectionProfileRequest,
): Promise<ApiInflectionProfile> {
  const response = await fetch(
    `/api/glossaries/${glossaryId}/terms/${tmTextUnitId}/inflection-profiles/${encodeURIComponent(
      localeTag,
    )}`,
    {
      method: 'PUT',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(request),
    },
  );

  if (!response.ok) {
    throw new Error(
      await getGlossaryApiErrorMessage(response, 'Failed to save inflection profile'),
    );
  }

  return (await response.json()) as ApiInflectionProfile;
}

function parseIntegerHeader(value: string | null): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0;
}

function parseTextHeader(value: string | null): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function parseContentDispositionFilename(value: string | null): string | null {
  if (!value) {
    return null;
  }

  const encodedFilename = value.match(/(?:^|;)\s*filename\*=UTF-8''([^;]+)/iu);
  if (encodedFilename?.[1]) {
    return decodeContentDispositionFilename(encodedFilename[1]);
  }

  const filename = value.match(/(?:^|;)\s*filename="?([^";]+)"?/iu);
  if (filename?.[1]) {
    return filename[1].trim() || null;
  }

  return null;
}

function decodeContentDispositionFilename(value: string): string {
  const trimmed = value.trim().replace(/^"|"$/gu, '');
  try {
    return decodeURIComponent(trimmed);
  } catch {
    return trimmed;
  }
}

function summarizeAuthoringInflectionPack(pack: unknown): {
  profileCount: number;
} {
  if (pack == null || typeof pack !== 'object' || Array.isArray(pack)) {
    return { profileCount: 0 };
  }

  const profiles = (pack as { profiles?: unknown }).profiles;
  return { profileCount: Array.isArray(profiles) ? profiles.length : 0 };
}

function summarizeCompiledInflectionPack(pack: unknown): {
  profileCount: number;
  formCount: number;
} {
  if (pack == null || typeof pack !== 'object' || Array.isArray(pack)) {
    return { profileCount: 0, formCount: 0 };
  }

  const formSetsValue = (pack as { formSets?: unknown }).formSets;
  if (!Array.isArray(formSetsValue)) {
    return { profileCount: 0, formCount: 0 };
  }
  const formSets = formSetsValue as unknown[];

  let formCount = 0;
  for (const formSet of formSets) {
    if (formSet == null || typeof formSet !== 'object' || Array.isArray(formSet)) {
      continue;
    }
    const formsValue = (formSet as { forms?: unknown }).forms;
    if (Array.isArray(formsValue)) {
      formCount += (formsValue as unknown[]).length;
    }
  }

  return { profileCount: formSets.length, formCount };
}

export async function fetchGlossaryWorkspaceSummary(
  glossaryId: number,
): Promise<ApiGlossaryWorkspaceSummary> {
  const response = await fetch(`/api/glossaries/${glossaryId}/workspace-summary`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary workspace summary');
  }

  return (await response.json()) as ApiGlossaryWorkspaceSummary;
}

export async function fetchGlossaryTermIndexSuggestions(
  glossaryId: number,
  options?: {
    search?: string;
    limit?: number;
    useAi?: boolean;
    includeReviewed?: boolean;
    reviewStatusFilter?: ApiGlossaryTermIndexSuggestionReviewStatusFilter;
    glossaryPresenceFilter?: ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter;
  },
): Promise<ApiGlossaryTermIndexSuggestionsResponse> {
  const response = await fetch(
    `/api/glossaries/${glossaryId}/term-index-suggestions/search-hybrid`,
    {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        search: options?.search?.trim() || null,
        limit: options?.limit ?? null,
        useAi: options?.useAi ?? null,
        includeReviewed: options?.includeReviewed ?? null,
        reviewStatusFilter: options?.reviewStatusFilter ?? null,
        glossaryPresenceFilter: options?.glossaryPresenceFilter ?? null,
      }),
    },
  );

  const payload = await parseGlossarySuggestionSearchHybridResponse(
    response,
    'Failed to load glossary term suggestions',
  );

  if (payload?.results) {
    return payload.results;
  }

  if (payload?.error) {
    throw createNonTransientError(
      payload.error.message || 'Failed to load glossary term suggestions',
    );
  }

  if (payload?.pollingToken) {
    return pollForGlossaryTermIndexSuggestions(glossaryId, payload.pollingToken);
  }

  throw new Error('Unexpected glossary term suggestion search response');
}

export async function acceptGlossaryTermIndexSuggestion(
  glossaryId: number,
  termIndexCandidateId: number,
  request: ApiAcceptGlossaryTermIndexSuggestionRequest,
): Promise<ApiGlossaryTerm> {
  const response = await fetch(
    `/api/glossaries/${glossaryId}/term-index-suggestions/${termIndexCandidateId}/accept`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      credentials: 'same-origin',
      body: JSON.stringify(request),
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to accept glossary term suggestion');
  }

  return (await response.json()) as ApiGlossaryTerm;
}

export async function ignoreGlossaryTermIndexSuggestion(
  glossaryId: number,
  termIndexCandidateId: number,
  request?: { reason?: string | null },
): Promise<void> {
  const response = await fetch(
    `/api/glossaries/${glossaryId}/term-index-suggestions/${termIndexCandidateId}/ignore`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      credentials: 'same-origin',
      body: JSON.stringify(request ?? {}),
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to ignore glossary term suggestion');
  }
}

export async function updateGlossaryTermIndexSuggestionReview(
  glossaryId: number,
  termIndexCandidateId: number,
  request: {
    reviewStatus: ApiTermIndexLayerReviewStatus;
    reviewReason?: string | null;
    reviewRationale?: string | null;
    reviewConfidence?: number | null;
  },
): Promise<{
  termIndexCandidateId: number;
  reviewStatus: ApiTermIndexLayerReviewStatus;
  reviewAuthority: ApiTermIndexLayerReviewAuthority;
  reviewReason?: string | null;
  reviewRationale?: string | null;
  reviewConfidence?: number | null;
  reviewChangedAt?: string | null;
  reviewChangedByUserId?: number | null;
  reviewChangedByUsername?: string | null;
  reviewChangedByCommonName?: string | null;
}> {
  const response = await fetch(
    `/api/glossaries/${glossaryId}/term-index-suggestions/${termIndexCandidateId}/review`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      credentials: 'same-origin',
      body: JSON.stringify(request),
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update glossary term suggestion review');
  }

  return (await response.json()) as {
    termIndexCandidateId: number;
    reviewStatus: ApiTermIndexLayerReviewStatus;
    reviewAuthority: ApiTermIndexLayerReviewAuthority;
    reviewReason?: string | null;
    reviewRationale?: string | null;
    reviewConfidence?: number | null;
    reviewChangedAt?: string | null;
    reviewChangedByUserId?: number | null;
    reviewChangedByUsername?: string | null;
    reviewChangedByCommonName?: string | null;
  };
}

export async function seedGlossaryTermIndexCandidates(
  glossaryId: number,
  request: ApiSeedGlossaryTermIndexCandidatesRequest,
): Promise<ApiSeedGlossaryTermIndexCandidatesResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/term-index-candidates`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to add glossary term index suggestion');
  }

  return (await response.json()) as ApiSeedGlossaryTermIndexCandidatesResponse;
}

export async function generateGlossaryTermIndexCandidates(
  glossaryId: number,
  request: ApiGenerateGlossaryTermIndexCandidatesRequest,
): Promise<ApiSeedGlossaryTermIndexCandidatesResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/term-index-candidates/generate`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to generate glossary term index suggestions');
  }

  const payload = (await response.json()) as ApiStartGlossaryExtractionResponse;
  const task = normalizePollableTask(payload.pollableTask);
  const completedTask = await waitForGlossaryExtractionTask(task.id, 600_000);
  if (completedTask.errorMessage) {
    throw new Error(normalizePollableTaskErrorMessage(completedTask.errorMessage));
  }
  return fetchGlossaryTermIndexCandidateGenerationOutput(task.id);
}

async function fetchGlossaryTermIndexCandidateGenerationOutput(
  pollableTaskId: number,
): Promise<ApiSeedGlossaryTermIndexCandidatesResponse> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}/output`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load generated glossary term index suggestions');
  }

  return (await response.json()) as ApiSeedGlossaryTermIndexCandidatesResponse;
}

export async function importGlossaryTermIndexCandidates(
  glossaryId: number,
  request: ApiImportGlossaryTermIndexCandidatesRequest,
): Promise<ApiSeedGlossaryTermIndexCandidatesResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/term-index-candidates/import`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify({
      format: request.format ?? 'json',
      content: request.content,
    }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to import glossary term index suggestions');
  }

  return (await response.json()) as ApiSeedGlossaryTermIndexCandidatesResponse;
}

export async function exportGlossaryTermIndexCandidates(
  glossaryId: number,
  options?: {
    search?: string | null;
    limit?: number | null;
  },
): Promise<Blob> {
  const params = new URLSearchParams();
  params.set('format', 'json');
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/glossaries/${glossaryId}/term-index-candidates/export?${params.toString()}`,
    {
      credentials: 'same-origin',
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to export glossary term index suggestions');
  }

  return await response.blob();
}

async function pollForGlossaryTermIndexSuggestions(
  glossaryId: number,
  pollingToken: {
    requestId: string;
    recommendedPollingDurationMillis?: number;
  },
): Promise<ApiGlossaryTermIndexSuggestionsResponse> {
  const timeoutMs = getGlossarySuggestionSearchPollingTimeoutMs(
    pollingToken.recommendedPollingDurationMillis,
  );

  const results = await poll<ApiGlossaryTermIndexSuggestionsResponse | null>(
    async () => {
      const response = await fetch(
        `/api/glossaries/${glossaryId}/term-index-suggestions/search-hybrid/results/${pollingToken.requestId}`,
        {
          credentials: 'same-origin',
          headers: { Accept: 'application/json' },
        },
      );
      const payload = await parseGlossarySuggestionSearchHybridResponse(
        response,
        'Failed to load glossary term suggestions',
      );

      if (payload?.results) {
        return payload.results;
      }
      if (payload?.error) {
        throw createNonTransientError(
          payload.error.message || 'Failed to load glossary term suggestions',
        );
      }
      return null;
    },
    {
      intervalMs: DEFAULT_POLL_INTERVAL_MS,
      maxIntervalMs: MAX_POLL_INTERVAL_MS,
      timeoutMs,
      timeoutMessage: 'Timed out while loading glossary term suggestions',
      isTransientError: isTransientHttpError,
      shouldStop: (response) => response !== null,
    },
  );

  return results ?? { suggestions: [], totalCount: 0 };
}

async function parseGlossarySuggestionSearchHybridResponse(
  response: Response,
  fallbackMessage: string,
): Promise<ApiGlossaryTermIndexSuggestionsHybridResponse | null> {
  const text = await response.text();
  const payload = parseGlossarySuggestionSearchHybridPayload(text);

  if (!response.ok) {
    if (payload?.error?.message) {
      throw createNonTransientError(payload.error.message);
    }
    const error: Error & { status?: number } = new Error(
      text || fallbackMessage || `Request failed with status ${response.status}`,
    );
    error.status = response.status;
    throw error;
  }

  return payload;
}

function parseGlossarySuggestionSearchHybridPayload(text: string) {
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as ApiGlossaryTermIndexSuggestionsHybridResponse;
  } catch {
    return null;
  }
}

function getGlossarySuggestionSearchPollingTimeoutMs(recommended?: number) {
  if (!Number.isFinite(recommended) || typeof recommended !== 'number' || recommended <= 0) {
    return GLOSSARY_SUGGESTION_SEARCH_POLL_TIMEOUT_MS;
  }
  return recommended;
}

function createNonTransientError(message: string) {
  const error: Error & { isTransient?: boolean } = new Error(message);
  error.isTransient = false;
  return error;
}

export async function createGlossary(
  request: ApiUpsertGlossaryRequest,
): Promise<ApiGlossaryDetail> {
  const response = await fetch('/api/glossaries', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create glossary');
  }

  return (await response.json()) as ApiGlossaryDetail;
}

export async function updateGlossary(
  glossaryId: number,
  request: ApiUpsertGlossaryRequest,
): Promise<ApiGlossaryDetail> {
  const response = await fetch(`/api/glossaries/${glossaryId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update glossary');
  }

  return (await response.json()) as ApiGlossaryDetail;
}

export async function createGlossaryTerm(
  glossaryId: number,
  request: ApiUpsertGlossaryTermRequest,
): Promise<ApiGlossaryTerm> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create glossary term');
  }

  return (await response.json()) as ApiGlossaryTerm;
}

export async function updateGlossaryTerm(
  glossaryId: number,
  tmTextUnitId: number,
  request: ApiUpsertGlossaryTermRequest,
): Promise<ApiGlossaryTerm> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/${tmTextUnitId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update glossary term');
  }

  return (await response.json()) as ApiGlossaryTerm;
}

export async function batchUpdateGlossaryTerms(
  glossaryId: number,
  request: ApiBatchUpdateGlossaryTermsRequest,
): Promise<ApiBatchUpdateGlossaryTermsResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/batch`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to batch update glossary terms');
  }

  return (await response.json()) as ApiBatchUpdateGlossaryTermsResponse;
}

export async function extractGlossaryTerms(
  glossaryId: number,
  request: ApiExtractGlossaryTermsRequest,
): Promise<ApiExtractGlossaryTermsResponse> {
  const startResponse = await startGlossaryTermExtraction(glossaryId, request);
  const completedTask = await waitForGlossaryExtractionTask(startResponse.id);
  if (completedTask.errorMessage) {
    throw new Error(normalizePollableTaskErrorMessage(completedTask.errorMessage));
  }
  return fetchGlossaryExtractionOutput(startResponse.id);
}

export async function startGlossaryTermExtraction(
  glossaryId: number,
  request: ApiExtractGlossaryTermsRequest,
): Promise<ApiPollableTask> {
  const response = await fetch(`/api/glossaries/${glossaryId}/extract`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to extract glossary candidates');
  }

  const payload = (await response.json()) as ApiStartGlossaryExtractionResponse;
  return normalizePollableTask(payload.pollableTask);
}

export async function fetchGlossaryExtractionTask(
  pollableTaskId: number,
): Promise<ApiPollableTask> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to poll glossary extraction status');
  }

  const payload = (await response.json()) as PollableTaskResponse;
  return normalizePollableTask(payload);
}

export async function waitForGlossaryExtractionTask(
  pollableTaskId: number,
  timeoutMs = 180_000,
): Promise<ApiPollableTask> {
  return poll(() => fetchGlossaryExtractionTask(pollableTaskId), {
    intervalMs: 1000,
    maxIntervalMs: 8000,
    timeoutMs,
    timeoutMessage: 'Timed out while waiting for glossary extraction',
    isTransientError: isTransientHttpError,
    shouldStop: (task) => task.isAllFinished,
  });
}

export async function fetchGlossaryExtractionOutput(
  pollableTaskId: number,
): Promise<ApiExtractGlossaryTermsResponse> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}/output`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary extraction output');
  }

  return (await response.json()) as ApiExtractGlossaryTermsResponse;
}

function normalizePollableTask(task: PollableTaskResponse): ApiPollableTask {
  const rawMessage = task.errorMessage;
  const normalizedMessage =
    typeof rawMessage === 'string' ? rawMessage : rawMessage ? JSON.stringify(rawMessage) : null;

  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    errorMessage: normalizedMessage,
  };
}

export async function deleteGlossary(glossaryId: number): Promise<void> {
  const response = await fetch(`/api/glossaries/${glossaryId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete glossary');
  }
}

export async function deleteGlossaryTerm(glossaryId: number, tmTextUnitId: number): Promise<void> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/${tmTextUnitId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete glossary term');
  }
}

export async function submitGlossaryTranslationProposal(
  glossaryId: number,
  tmTextUnitId: number,
  payload: {
    localeTag: string;
    target: string;
    targetComment?: string | null;
    note?: string | null;
  },
): Promise<ApiGlossaryTranslationProposal> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/${tmTextUnitId}/proposals`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to submit glossary proposal');
  }

  return (await response.json()) as ApiGlossaryTranslationProposal;
}

export async function fetchGlossaryTranslationProposals(
  glossaryId: number,
  options?: { status?: string; limit?: number },
): Promise<ApiGlossaryTranslationProposalsResponse> {
  const params = new URLSearchParams();
  if (options?.status?.trim()) {
    params.set('status', options.status.trim());
  }
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/glossaries/${glossaryId}/proposals${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary proposals');
  }

  return (await response.json()) as ApiGlossaryTranslationProposalsResponse;
}

export async function decideGlossaryTranslationProposal(
  glossaryId: number,
  proposalId: number,
  payload: { status: 'ACCEPTED' | 'REJECTED'; reviewerNote?: string | null },
): Promise<ApiGlossaryTranslationProposal> {
  const response = await fetch(`/api/glossaries/${glossaryId}/proposals/${proposalId}/decision`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to decide glossary proposal');
  }

  return (await response.json()) as ApiGlossaryTranslationProposal;
}

export async function matchGlossaryTerms(
  request: ApiMatchGlossaryTermsRequest,
): Promise<ApiMatchGlossaryTermsResponse> {
  const response = await fetch('/api/glossaries/match', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to match glossary terms');
  }

  return (await response.json()) as ApiMatchGlossaryTermsResponse;
}

export async function exportGlossary(glossaryId: number): Promise<Blob> {
  const response = await fetch(`/api/glossaries/${glossaryId}/export?format=json`, {
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to export glossary');
  }

  return await response.blob();
}

export async function importGlossary(
  glossaryId: number,
  request: { content: string },
): Promise<ApiImportGlossaryResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/import`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify({ format: 'json', content: request.content }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to import glossary');
  }

  return (await response.json()) as ApiImportGlossaryResponse;
}
