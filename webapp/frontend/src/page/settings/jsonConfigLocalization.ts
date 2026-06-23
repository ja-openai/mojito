import type { ApiTextUnit, ApiTextUnitStatus } from '../../api/text-units';

export type JsonConfigLocalizationDraftString = {
  clientId: string;
  tmTextUnitId?: number | null;
  assetId?: number | null;
  createdDate?: string | null;
  stringId: string;
  source: string;
  comment: string;
  used: boolean;
  doNotTranslate: boolean;
};

export type JsonConfigLocalizationLocaleReadiness = {
  localeTag: string;
  target: string;
  status: JsonConfigLocalizationReadinessStatus;
  translated: boolean;
  reviewed: boolean;
};

export type JsonConfigLocalizationStringReadiness = {
  translatedCount: number;
  reviewedCount: number;
  totalTargetLocales: number;
  locales: JsonConfigLocalizationLocaleReadiness[];
};

export type JsonConfigLocalizationReadinessStatus =
  | 'MISSING'
  | 'TRANSLATION_NEEDED'
  | 'REVIEW_NEEDED'
  | 'APPROVED'
  | 'EXCLUDED';

export type JsonConfigLocalizationExport = Record<string, Record<string, string>>;
export type JsonConfigLocalizationLocaleFileExport = {
  localeTag: string;
  filename: string;
  messages: Record<string, string>;
};
export type OutputLocaleMapping = Record<string, string>;
export type OutputLocaleMappingParseResult = {
  mapping: OutputLocaleMapping;
  warnings: string[];
};

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonRecord = { [key: string]: JsonValue };
type JsonRecordPathMatch = { path: string; value: JsonRecord };
export type JsonConfigSourceFormat =
  | 'EMBEDDED_TRANSLATIONS'
  | 'FLAT_SOURCE_ARRAY'
  | 'FORMATJS_MAP'
  | 'FORMATJS_MULTILINGUAL_MAP';

export type StatsigSourceConfigProfile = {
  format?: JsonConfigSourceFormat;
  collectionKey: string;
  itemIdField: string;
  translationsField: string;
  sourceLocaleTag: string;
  translatableFields: string[];
  sourceField?: string;
  commentField?: string;
};

export type StatsigSourceConfigExtraction = {
  profile: StatsigSourceConfigProfile;
  sourceConfig: JsonRecord;
  strings: JsonConfigLocalizationDraftString[];
  warnings: string[];
};

export type StatsigSourceConfigExportResult = {
  value: JsonRecord;
  warnings: string[];
};

export type StatsigSourceConfigAppendEntryResult = {
  sourceConfigJson: string;
  profile: StatsigSourceConfigProfile;
  itemKey: string;
  stringIds: string[];
  appended: boolean;
  warnings: string[];
};

export const DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH = 'json-config-localization/strings.json';
export const DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE: StatsigSourceConfigProfile = {
  format: 'EMBEDDED_TRANSLATIONS',
  collectionKey: '',
  itemIdField: 'id',
  translationsField: 'translations',
  sourceLocaleTag: 'en-US',
  translatableFields: [],
  sourceField: 'source',
  commentField: 'description',
};

const sourceStringIdCollator = new Intl.Collator(undefined, {
  numeric: true,
  sensitivity: 'base',
});

export function sortJsonConfigLocalizationDraftStrings(
  sourceStrings: JsonConfigLocalizationDraftString[],
): JsonConfigLocalizationDraftString[] {
  return [...sourceStrings].sort((left, right) => {
    const stringIdCompare = sourceStringIdCollator.compare(left.stringId, right.stringId);
    if (stringIdCompare !== 0) {
      return stringIdCompare;
    }
    return sourceStringIdCollator.compare(left.clientId, right.clientId);
  });
}

export function toJsonConfigLocalizationDraftStrings(
  textUnits: ApiTextUnit[],
): JsonConfigLocalizationDraftString[] {
  return sortJsonConfigLocalizationDraftStrings(
    textUnits.map((textUnit) => ({
      clientId: buildExistingClientId(textUnit.tmTextUnitId),
      tmTextUnitId: textUnit.tmTextUnitId,
      assetId: textUnit.assetId,
      createdDate: textUnit.tmTextUnitCreatedDate ?? textUnit.createdDate ?? null,
      stringId: textUnit.name,
      source: textUnit.source ?? '',
      comment: textUnit.comment ?? '',
      used: textUnit.used,
      doNotTranslate: textUnit.doNotTranslate ?? false,
    })),
  );
}

export function createEmptyDraftString(existingCount: number): JsonConfigLocalizationDraftString {
  return {
    clientId: `draft-${Date.now()}-${existingCount}`,
    tmTextUnitId: null,
    assetId: null,
    createdDate: null,
    stringId: '',
    source: '',
    comment: '',
    used: true,
    doNotTranslate: false,
  };
}

export function buildJsonConfigLocalizationReadiness(
  sourceStrings: JsonConfigLocalizationDraftString[],
  targetRows: ApiTextUnit[],
  targetLocaleTags: string[],
): Record<string, JsonConfigLocalizationStringReadiness> {
  const targetByTextUnitAndLocale = new Map<string, ApiTextUnit>();

  targetRows.forEach((row) => {
    targetByTextUnitAndLocale.set(buildTargetKey(row.tmTextUnitId, row.targetLocale), row);
  });

  return Object.fromEntries(
    sourceStrings.map((sourceString) => {
      const locales = targetLocaleTags.map((localeTag) =>
        buildLocaleReadiness(sourceString.tmTextUnitId, localeTag, targetByTextUnitAndLocale),
      );

      return [
        sourceString.clientId,
        {
          translatedCount: locales.filter((locale) => locale.translated).length,
          reviewedCount: locales.filter((locale) => locale.reviewed).length,
          totalTargetLocales: targetLocaleTags.length,
          locales,
        },
      ];
    }),
  );
}

export function buildJsonConfigLocalizationExport(
  sourceLocaleTag: string,
  targetLocaleTags: string[],
  sourceStrings: JsonConfigLocalizationDraftString[],
  readinessByString: Record<string, JsonConfigLocalizationStringReadiness>,
  outputLocaleByMojitoLocale: OutputLocaleMapping = {},
): JsonConfigLocalizationExport {
  const activeStrings = sourceStrings.filter((sourceString) => sourceString.used);
  const localeMap: JsonConfigLocalizationExport = {
    [sourceLocaleTag]: Object.fromEntries(
      activeStrings.map((sourceString) => [sourceString.stringId, sourceString.source]),
    ),
  };

  targetLocaleTags.forEach((localeTag) => {
    const outputLocaleTag = getMappedLocaleTag(localeTag, outputLocaleByMojitoLocale);
    localeMap[outputLocaleTag] = Object.fromEntries(
      activeStrings.flatMap((sourceString): [string, string][] => {
        const target = getTranslatedTarget(sourceString, localeTag, readinessByString);
        return target ? [[sourceString.stringId, target]] : [];
      }),
    );
  });

  return localeMap;
}

export function buildJsonConfigLocalizationLocaleFileExport(
  sourceLocaleTag: string,
  targetLocaleTags: string[],
  sourceStrings: JsonConfigLocalizationDraftString[],
  readinessByString: Record<string, JsonConfigLocalizationStringReadiness>,
  outputLocaleByMojitoLocale: OutputLocaleMapping = {},
): JsonConfigLocalizationLocaleFileExport[] {
  return Object.entries(
    buildJsonConfigLocalizationExport(
      sourceLocaleTag,
      targetLocaleTags,
      sourceStrings,
      readinessByString,
      outputLocaleByMojitoLocale,
    ),
  ).map(([localeTag, messages]) => ({
    localeTag,
    filename: `${toSafeLocaleFilename(localeTag)}.json`,
    messages,
  }));
}

export function extractStatsigSourceConfigStrings(
  schemaText: string,
  sourceConfigText: string,
  existingCount = 0,
  profileOverride?: Partial<StatsigSourceConfigProfile>,
): StatsigSourceConfigExtraction {
  const sourceConfig = parseJsonRecord(sourceConfigText, 'config');
  const { profile: detectedProfile, warnings: schemaWarnings } = tryDetectStatsigProfileForFormat(
    schemaText,
    profileOverride,
  );
  const profile = normalizeStatsigProfile(
    inferStatsigProfileFromSource(sourceConfig, {
      ...detectedProfile,
      ...profileOverride,
    }),
  );
  if (profile.format === 'FLAT_SOURCE_ARRAY') {
    return extractFlatSourceArrayStrings(sourceConfig, existingCount, profile, schemaWarnings);
  }
  if (isFormatJsSourceFormat(profile.format)) {
    return extractFormatJsMapStrings(sourceConfig, existingCount, profile, schemaWarnings);
  }
  const collection = sourceConfig[profile.collectionKey];

  if (!Array.isArray(collection)) {
    throw new Error(`Config must contain an array at "${profile.collectionKey}".`);
  }

  const warnings = [...schemaWarnings];
  const warnOnMissingItemId = shouldWarnOnMissingItemId(schemaText, collection, profile);
  const seenStringIds = new Set<string>();
  const strings: JsonConfigLocalizationDraftString[] = [];

  collection.forEach((item, index) => {
    if (!isJsonRecord(item)) {
      warnings.push(`${profile.collectionKey}[${index}] is not an object; skipped.`);
      return;
    }

    const itemKey = resolveStatsigItemKey(item, index, profile, warnings, warnOnMissingItemId);

    const translations = item[profile.translationsField];
    const sourceTranslation = isJsonRecord(translations)
      ? translations[profile.sourceLocaleTag]
      : undefined;

    if (!isJsonRecord(sourceTranslation)) {
      warnings.push(
        `${profile.collectionKey}[${index}] is missing ${profile.translationsField}.${profile.sourceLocaleTag}; skipped.`,
      );
      return;
    }

    profile.translatableFields.forEach((field) => {
      const source = sourceTranslation[field];
      const stringId = `${itemKey}.${field}`;

      if (seenStringIds.has(stringId)) {
        warnings.push(`Duplicate string id "${stringId}" skipped.`);
        return;
      }
      seenStringIds.add(stringId);

      if (typeof source !== 'string') {
        warnings.push(`${stringId} is missing source text; skipped.`);
        return;
      }

      strings.push({
        clientId: `statsig-${existingCount + strings.length}-${normalizeSourceStringId(stringId)}`,
        tmTextUnitId: null,
        assetId: null,
        stringId,
        source,
        comment: '',
        used: true,
        doNotTranslate: false,
      });
    });
  });

  return {
    profile,
    sourceConfig,
    strings,
    warnings,
  };
}

function extractFlatSourceArrayStrings(
  sourceConfig: JsonRecord,
  existingCount: number,
  profile: StatsigSourceConfigProfile,
  schemaWarnings: string[],
): StatsigSourceConfigExtraction {
  const collection = sourceConfig[profile.collectionKey];
  if (!Array.isArray(collection)) {
    throw new Error(`Config must contain an array at "${profile.collectionKey}".`);
  }

  const warnings = [...schemaWarnings];
  const strings: JsonConfigLocalizationDraftString[] = [];
  const seenStringIds = new Set<string>();
  const sourceField = profile.sourceField || 'source';
  const commentField = profile.commentField || 'description';

  collection.forEach((item, index) => {
    if (!isJsonRecord(item)) {
      warnings.push(`${profile.collectionKey}[${index}] is not an object; skipped.`);
      return;
    }

    const itemKey = resolveStatsigItemKey(item, index, profile, warnings, true);
    if (seenStringIds.has(itemKey)) {
      warnings.push(`Duplicate string id "${itemKey}" skipped.`);
      return;
    }
    seenStringIds.add(itemKey);

    const source = item[sourceField];
    if (typeof source !== 'string') {
      warnings.push(`${itemKey} is missing source text; skipped.`);
      return;
    }

    const comment = item[commentField];
    strings.push({
      clientId: `json-flat-${existingCount + strings.length}-${normalizeSourceStringId(itemKey)}`,
      tmTextUnitId: null,
      assetId: null,
      stringId: itemKey,
      source,
      comment: typeof comment === 'string' ? comment : '',
      used: true,
      doNotTranslate: false,
    });
  });

  return { profile, sourceConfig, strings, warnings };
}

function extractFormatJsMapStrings(
  sourceConfig: JsonRecord,
  existingCount: number,
  profile: StatsigSourceConfigProfile,
  schemaWarnings: string[],
): StatsigSourceConfigExtraction {
  const warnings = [...schemaWarnings];
  const strings: JsonConfigLocalizationDraftString[] = [];
  const sourceField = profile.sourceField || 'defaultMessage';
  const commentField = profile.commentField || 'description';
  const seenStringIds = new Set<string>();

  getFormatJsMessageMaps(sourceConfig, profile).forEach((messageMapMatch) => {
    Object.entries(messageMapMatch.value).forEach(([stringId, value]) => {
      const stringPath = formatJsonPath(messageMapMatch.path, stringId);
      if (!isJsonRecord(value)) {
        warnings.push(`${stringPath} is not an object; skipped.`);
        return;
      }

      const source = value[sourceField];
      if (typeof source !== 'string') {
        warnings.push(`${stringPath} is missing ${sourceField}; skipped.`);
        return;
      }
      if (seenStringIds.has(stringId)) {
        warnings.push(`Duplicate string id "${stringId}" at "${stringPath}" skipped.`);
        return;
      }
      seenStringIds.add(stringId);

      const comment = value[commentField];
      strings.push({
        clientId: `formatjs-${existingCount + strings.length}-${normalizeSourceStringId(stringId)}`,
        tmTextUnitId: null,
        assetId: null,
        stringId,
        source,
        comment: typeof comment === 'string' ? comment : '',
        used: true,
        doNotTranslate: false,
      });
    });
  });

  return { profile, sourceConfig, strings, warnings };
}

export function inferStatsigSourceConfigProfileFromText(
  sourceConfigText: string,
  profileOverride: StatsigSourceConfigProfile,
): StatsigSourceConfigProfile {
  const sourceConfig = parseJsonRecord(sourceConfigText, 'config');
  return normalizeStatsigProfile(inferStatsigProfileFromSource(sourceConfig, profileOverride));
}

export function getStatsigSourceConfigStringIds(
  sourceConfigText: string,
  profileOverride: StatsigSourceConfigProfile,
): Set<string> {
  const sourceConfig = parseJsonRecord(sourceConfigText, 'config');
  const profile = normalizeStatsigProfile(
    inferStatsigProfileFromSource(sourceConfig, profileOverride),
  );
  if (profile.format === 'FLAT_SOURCE_ARRAY') {
    const collection = sourceConfig[profile.collectionKey];
    if (!Array.isArray(collection)) {
      throw new Error(`Config must contain an array at "${profile.collectionKey}".`);
    }
    const warnings: string[] = [];
    return new Set(
      collection.flatMap((item, index): string[] =>
        isJsonRecord(item) ? [resolveStatsigItemKey(item, index, profile, warnings, false)] : [],
      ),
    );
  }
  if (isFormatJsSourceFormat(profile.format)) {
    return new Set(
      getFormatJsMessageMaps(sourceConfig, profile).flatMap((match) => Object.keys(match.value)),
    );
  }
  const collection = sourceConfig[profile.collectionKey];

  if (!Array.isArray(collection)) {
    throw new Error(`Config must contain an array at "${profile.collectionKey}".`);
  }

  const warnings: string[] = [];
  const stringIds = new Set<string>();
  collection.forEach((item, index) => {
    if (!isJsonRecord(item)) {
      return;
    }

    const itemKey = resolveStatsigItemKey(item, index, profile, warnings, false);
    const translations = item[profile.translationsField];
    const sourceTranslation = isJsonRecord(translations)
      ? translations[profile.sourceLocaleTag]
      : undefined;

    if (!isJsonRecord(sourceTranslation)) {
      return;
    }

    profile.translatableFields.forEach((field) => {
      if (typeof sourceTranslation[field] === 'string') {
        stringIds.add(`${itemKey}.${field}`);
      }
    });
  });

  return stringIds;
}

export function mergeExtractedDraftStrings(
  currentStrings: JsonConfigLocalizationDraftString[],
  extractedStrings: JsonConfigLocalizationDraftString[],
): JsonConfigLocalizationDraftString[] {
  const currentByStringId = new Map<string, JsonConfigLocalizationDraftString[]>();
  currentStrings.forEach((sourceString) => {
    const strings = currentByStringId.get(sourceString.stringId) ?? [];
    strings.push(sourceString);
    currentByStringId.set(sourceString.stringId, strings);
  });
  const consumedClientIds = new Set<string>();
  const extractedStringIds = new Set(extractedStrings.map((sourceString) => sourceString.stringId));

  const mergedExtracted = extractedStrings.map((extractedString) => {
    const currentStringsForId = currentByStringId.get(extractedString.stringId) ?? [];
    const exactCurrentString = currentStringsForId.find(
      (currentString) =>
        currentString.source === extractedString.source &&
        commentsMatchForExtraction(currentString.comment, extractedString.comment),
    );
    if (!exactCurrentString) {
      return extractedString;
    }

    consumedClientIds.add(exactCurrentString.clientId);
    return {
      ...exactCurrentString,
      source: extractedString.source,
      comment: extractedString.comment,
      used: true,
      doNotTranslate: extractedString.doNotTranslate,
    };
  });

  const removedStrings = currentStrings
    .filter(
      (sourceString) =>
        !consumedClientIds.has(sourceString.clientId) ||
        !extractedStringIds.has(sourceString.stringId),
    )
    .map((sourceString) => ({ ...sourceString, used: false }));

  return sortJsonConfigLocalizationDraftStrings([...mergedExtracted, ...removedStrings]);
}

function commentsMatchForExtraction(currentComment: string, extractedComment: string): boolean {
  if (currentComment === extractedComment) {
    return true;
  }

  return extractedComment.trim() === '' && isGeneratedPathComment(currentComment);
}

function isGeneratedPathComment(comment: string): boolean {
  const trimmedComment = comment.trim();
  return /^[A-Za-z0-9_.$-]+\[[^\]]+\]\.(?:[A-Za-z0-9_$-]+\.)?[A-Za-z0-9_$-]+$/.test(trimmedComment);
}

export function buildStatsigSourceConfigExport(
  profile: StatsigSourceConfigProfile,
  sourceConfig: JsonRecord,
  targetLocaleTags: string[],
  sourceStrings: JsonConfigLocalizationDraftString[],
  readinessByString: Record<string, JsonConfigLocalizationStringReadiness>,
  outputLocaleByMojitoLocale: OutputLocaleMapping = {},
): StatsigSourceConfigExportResult {
  const normalizedProfile = normalizeStatsigProfile(
    inferStatsigProfileFromSource(sourceConfig, profile),
  );
  if (normalizedProfile.format === 'FLAT_SOURCE_ARRAY') {
    return buildFlatSourceArrayConfigExport(normalizedProfile, sourceConfig, sourceStrings);
  }
  if (normalizedProfile.format === 'FORMATJS_MULTILINGUAL_MAP') {
    return buildFormatJsMultilingualMapConfigExport(
      normalizedProfile,
      sourceConfig,
      targetLocaleTags,
      sourceStrings,
      readinessByString,
      outputLocaleByMojitoLocale,
    );
  }
  if (normalizedProfile.format === 'FORMATJS_MAP') {
    return buildFormatJsMapConfigExport(normalizedProfile, sourceConfig, sourceStrings);
  }
  const sourceStringById = new Map(
    sourceStrings.map((sourceString) => [sourceString.stringId, sourceString]),
  );
  const warnings: string[] = [];
  const output = cloneJsonRecord(sourceConfig);
  const collection = output[normalizedProfile.collectionKey];
  const existingItemKeys = new Set<string>();

  if (!Array.isArray(collection)) {
    return {
      value: output,
      warnings: [`Config must contain an array at "${normalizedProfile.collectionKey}".`],
    };
  }

  const warnOnMissingItemId = collectionHasUsableItemId(collection, normalizedProfile.itemIdField);

  collection.forEach((item, index) => {
    if (!isJsonRecord(item)) {
      warnings.push(`${normalizedProfile.collectionKey}[${index}] is not an object; skipped.`);
      return;
    }

    const itemKey = resolveStatsigItemKey(
      item,
      index,
      normalizedProfile,
      warnings,
      warnOnMissingItemId,
    );
    existingItemKeys.add(itemKey);

    const translations = ensureJsonRecord(item, normalizedProfile.translationsField);
    const seenOutputLocales = new Set<string>();

    const sourceLocaleObject = ensureJsonRecord(translations, normalizedProfile.sourceLocaleTag);
    const originalSourceFields = translatableTextFields(
      sourceLocaleObject,
      normalizedProfile.translatableFields,
    );
    clearTranslatableFields(sourceLocaleObject, normalizedProfile.translatableFields);
    seenOutputLocales.add(normalizedProfile.sourceLocaleTag);

    normalizedProfile.translatableFields.forEach((field) => {
      const stringId = `${itemKey}.${field}`;
      const sourceString = sourceStringById.get(stringId);
      if (!sourceString) {
        const originalSource = originalSourceFields[field];
        if (originalSource) {
          sourceLocaleObject[field] = originalSource;
        } else {
          warnings.push(`Missing source string "${stringId}".`);
        }
        return;
      }
      if (!sourceString.used) {
        return;
      }

      sourceLocaleObject[field] = sourceString.source;
    });

    targetLocaleTags.forEach((mojitoLocaleTag) => {
      const outputLocaleTag = getMappedLocaleTag(mojitoLocaleTag, outputLocaleByMojitoLocale);
      if (seenOutputLocales.has(outputLocaleTag)) {
        warnings.push(`Multiple Mojito locales map to output locale "${outputLocaleTag}".`);
        return;
      }
      seenOutputLocales.add(outputLocaleTag);

      const localeValue = translations[outputLocaleTag];
      const localeObject = isJsonRecord(localeValue) ? localeValue : {};
      clearTranslatableFields(localeObject, normalizedProfile.translatableFields);
      let translatedFieldCount = 0;

      normalizedProfile.translatableFields.forEach((field) => {
        const stringId = `${itemKey}.${field}`;
        const sourceString = sourceStringById.get(stringId);
        const target = sourceString?.used
          ? getTranslatedTarget(sourceString, mojitoLocaleTag, readinessByString)
          : '';

        if (target) {
          localeObject[field] = target;
          translatedFieldCount += 1;
        }
      });

      if (
        translatedFieldCount > 0 ||
        hasNonTranslatableFields(localeObject, normalizedProfile.translatableFields)
      ) {
        translations[outputLocaleTag] = localeObject;
      } else {
        delete translations[outputLocaleTag];
      }
    });
  });

  const missingSourceStringsByItemKey = new Map<
    string,
    Map<string, JsonConfigLocalizationDraftString>
  >();
  sourceStrings
    .filter((sourceString) => sourceString.used)
    .forEach((sourceString) => {
      const parsedStringId = parseStatsigStringId(
        sourceString.stringId,
        normalizedProfile.translatableFields,
      );
      if (!parsedStringId) {
        warnings.push(
          `Active string "${sourceString.stringId}" was not written to config because it does not match the configured fields.`,
        );
        return;
      }
      if (existingItemKeys.has(parsedStringId.itemKey)) {
        return;
      }

      const itemFields =
        missingSourceStringsByItemKey.get(parsedStringId.itemKey) ??
        new Map<string, JsonConfigLocalizationDraftString>();
      itemFields.set(parsedStringId.field, sourceString);
      missingSourceStringsByItemKey.set(parsedStringId.itemKey, itemFields);
    });

  missingSourceStringsByItemKey.forEach((itemFields, itemKey) => {
    const sourceLocaleObject: JsonRecord = {};
    normalizedProfile.translatableFields.forEach((field) => {
      const sourceString = itemFields.get(field);
      if (sourceString) {
        sourceLocaleObject[field] = sourceString.source;
      }
    });

    if (!Object.keys(sourceLocaleObject).length) {
      return;
    }

    const translations: JsonRecord = {
      [normalizedProfile.sourceLocaleTag]: sourceLocaleObject,
    };
    const seenOutputLocales = new Set<string>([normalizedProfile.sourceLocaleTag]);
    targetLocaleTags.forEach((mojitoLocaleTag) => {
      const outputLocaleTag = getMappedLocaleTag(mojitoLocaleTag, outputLocaleByMojitoLocale);
      if (seenOutputLocales.has(outputLocaleTag)) {
        warnings.push(`Multiple Mojito locales map to output locale "${outputLocaleTag}".`);
        return;
      }
      seenOutputLocales.add(outputLocaleTag);

      const localeObject: JsonRecord = {};
      itemFields.forEach((sourceString, field) => {
        const target = getTranslatedTarget(sourceString, mojitoLocaleTag, readinessByString);
        if (target) {
          localeObject[field] = target;
        }
      });

      if (Object.keys(localeObject).length) {
        translations[outputLocaleTag] = localeObject;
      }
    });

    const item: JsonRecord = {
      [normalizedProfile.translationsField]: translations,
    };
    if (itemKey !== fallbackItemKey(normalizedProfile, collection.length)) {
      item[normalizedProfile.itemIdField] = itemKey;
    }
    collection.push(item);
    existingItemKeys.add(itemKey);
  });

  return {
    value: output,
    warnings: Array.from(new Set(warnings)),
  };
}

function buildFlatSourceArrayConfigExport(
  profile: StatsigSourceConfigProfile,
  sourceConfig: JsonRecord,
  sourceStrings: JsonConfigLocalizationDraftString[],
): StatsigSourceConfigExportResult {
  const warnings: string[] = [];
  const output = cloneJsonRecord(sourceConfig);
  const collection = output[profile.collectionKey];
  const sourceStringById = new Map(
    sourceStrings.map((sourceString) => [sourceString.stringId, sourceString]),
  );
  const existingItemKeys = new Set<string>();
  const sourceField = profile.sourceField || 'source';
  const commentField = profile.commentField || 'description';

  if (!Array.isArray(collection)) {
    return {
      value: output,
      warnings: [`Config must contain an array at "${profile.collectionKey}".`],
    };
  }

  collection.forEach((item, index) => {
    if (!isJsonRecord(item)) {
      warnings.push(`${profile.collectionKey}[${index}] is not an object; skipped.`);
      return;
    }

    const itemKey = resolveStatsigItemKey(item, index, profile, warnings, true);
    existingItemKeys.add(itemKey);
    const sourceString = sourceStringById.get(itemKey);
    if (!sourceString?.used) {
      return;
    }

    item[sourceField] = sourceString.source;
    if (sourceString.comment || item[commentField] != null) {
      item[commentField] = sourceString.comment;
    }
  });

  sourceStrings
    .filter((sourceString) => sourceString.used && !existingItemKeys.has(sourceString.stringId))
    .forEach((sourceString) => {
      const item: JsonRecord = {
        [profile.itemIdField]: sourceString.stringId,
        [sourceField]: sourceString.source,
      };
      if (sourceString.comment) {
        item[commentField] = sourceString.comment;
      }
      collection.push(item);
    });

  return {
    value: output,
    warnings: Array.from(new Set(warnings)),
  };
}

function buildFormatJsMapConfigExport(
  profile: StatsigSourceConfigProfile,
  sourceConfig: JsonRecord,
  sourceStrings: JsonConfigLocalizationDraftString[],
): StatsigSourceConfigExportResult {
  const output = cloneJsonRecord(sourceConfig);
  const sourceField = profile.sourceField || 'defaultMessage';
  const commentField = profile.commentField || 'description';
  const messageMapMatches = writableFormatJsMessageMaps(output, profile);
  const warnings: string[] = [];

  sourceStrings
    .filter((sourceString) => sourceString.used)
    .forEach((sourceString) => {
      const messageMapMatch = writableFormatJsMessageMapForString(
        messageMapMatches,
        sourceString.stringId,
        profile,
        warnings,
      );
      const entry = ensureJsonRecord(messageMapMatch.value, sourceString.stringId);
      entry[sourceField] = sourceString.source;
      if (sourceString.comment || entry[commentField] != null) {
        entry[commentField] = sourceString.comment;
      }
    });

  return {
    value: output,
    warnings: Array.from(new Set(warnings)),
  };
}

function buildFormatJsMultilingualMapConfigExport(
  profile: StatsigSourceConfigProfile,
  sourceConfig: JsonRecord,
  targetLocaleTags: string[],
  sourceStrings: JsonConfigLocalizationDraftString[],
  readinessByString: Record<string, JsonConfigLocalizationStringReadiness>,
  outputLocaleByMojitoLocale: OutputLocaleMapping = {},
): StatsigSourceConfigExportResult {
  const output = cloneJsonRecord(sourceConfig);
  const sourceField = profile.sourceField || 'defaultMessage';
  const commentField = profile.commentField || 'description';
  const translationsField = profile.translationsField || 'translations';
  const messageMapMatches = writableFormatJsMessageMaps(output, profile);
  const warnings: string[] = [];

  sourceStrings
    .filter((sourceString) => sourceString.used)
    .forEach((sourceString) => {
      const messageMapMatch = writableFormatJsMessageMapForString(
        messageMapMatches,
        sourceString.stringId,
        profile,
        warnings,
      );
      const entry = ensureJsonRecord(messageMapMatch.value, sourceString.stringId);
      entry[sourceField] = sourceString.source;
      if (sourceString.comment || entry[commentField] != null) {
        entry[commentField] = sourceString.comment;
      }

      const translations = ensureJsonRecord(entry, translationsField);
      const seenOutputLocales = new Set<string>();
      targetLocaleTags.forEach((mojitoLocaleTag) => {
        const outputLocaleTag = getMappedLocaleTag(mojitoLocaleTag, outputLocaleByMojitoLocale);
        if (seenOutputLocales.has(outputLocaleTag)) {
          warnings.push(`Multiple Mojito locales map to output locale "${outputLocaleTag}".`);
          return;
        }
        seenOutputLocales.add(outputLocaleTag);

        const target = getTranslatedTarget(sourceString, mojitoLocaleTag, readinessByString);
        if (target) {
          translations[outputLocaleTag] = target;
        } else {
          delete translations[outputLocaleTag];
        }
      });
    });

  return {
    value: output,
    warnings: Array.from(new Set(warnings)),
  };
}

function parseStatsigStringId(
  stringId: string,
  translatableFields: string[],
): { itemKey: string; field: string } | null {
  const trimmedStringId = stringId.trim();
  const field = [...translatableFields]
    .sort((left, right) => right.length - left.length)
    .find((candidate) => trimmedStringId.endsWith(`.${candidate}`));
  if (!field) {
    return null;
  }

  const itemKey = trimmedStringId.slice(0, -field.length - 1);
  return itemKey ? { itemKey, field } : null;
}

export function appendStatsigSourceConfigEntry(
  schemaText: string,
  sourceConfigText: string,
  profileOverride: StatsigSourceConfigProfile,
): StatsigSourceConfigAppendEntryResult {
  const warnings: string[] = [];
  const sourceConfig = sourceConfigText.trim() ? parseJsonRecord(sourceConfigText, 'config') : {};
  const { profile: detectedProfile, warnings: schemaWarnings } = tryDetectStatsigProfileForFormat(
    schemaText,
    profileOverride,
  );
  warnings.push(...schemaWarnings);
  const profile = normalizeStatsigProfile(
    inferStatsigProfileFromSource(sourceConfig, {
      ...detectedProfile,
      ...profileOverride,
    }),
  );
  if (profile.format === 'FLAT_SOURCE_ARRAY') {
    return appendFlatSourceArrayEntry(sourceConfig, profile, warnings);
  }
  if (profile.format === 'FORMATJS_MULTILINGUAL_MAP') {
    return appendFormatJsMultilingualMapEntry(sourceConfig, profile, warnings);
  }
  if (profile.format === 'FORMATJS_MAP') {
    return appendFormatJsMapEntry(sourceConfig, profile, warnings);
  }
  const collectionValue = sourceConfig[profile.collectionKey];
  const collection: JsonValue[] = Array.isArray(collectionValue) ? collectionValue : [];

  if (collectionValue == null) {
    sourceConfig[profile.collectionKey] = collection;
  } else if (!Array.isArray(collectionValue)) {
    throw new Error(`Config must contain an array at "${profile.collectionKey}".`);
  }

  const nextIndex = collection.length;
  const includeItemId = shouldAppendItemId(schemaText, profile);
  const itemKey = includeItemId
    ? nextStatsigItemId(collection, profile.itemIdField)
    : fallbackItemKey(profile, nextIndex);
  const sourceLocaleObject: JsonRecord = Object.fromEntries(
    profile.translatableFields.map((field) => [field, '']),
  );
  const item: JsonRecord = {
    [profile.translationsField]: {
      [profile.sourceLocaleTag]: sourceLocaleObject,
    },
  };

  if (includeItemId) {
    item[profile.itemIdField] = itemKey;
  } else {
    warnings.push(
      `Schema does not define "${profile.itemIdField}", so the new item uses index-based string ids under "${itemKey}".`,
    );
  }

  collection.push(item);

  return {
    sourceConfigJson: JSON.stringify(sourceConfig, null, 2),
    profile,
    itemKey,
    stringIds: profile.translatableFields.map((field) => `${itemKey}.${field}`),
    appended: true,
    warnings: Array.from(new Set(warnings)),
  };
}

function appendFlatSourceArrayEntry(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
  warnings: string[],
): StatsigSourceConfigAppendEntryResult {
  const collectionValue = sourceConfig[profile.collectionKey];
  const collection: JsonValue[] = Array.isArray(collectionValue) ? collectionValue : [];

  if (collectionValue == null) {
    sourceConfig[profile.collectionKey] = collection;
  } else if (!Array.isArray(collectionValue)) {
    throw new Error(`Config must contain an array at "${profile.collectionKey}".`);
  }

  const itemKey = nextStatsigItemId(collection, profile.itemIdField);
  const sourceField = profile.sourceField || 'source';
  const commentField = profile.commentField || 'description';
  collection.push({
    [profile.itemIdField]: itemKey,
    [sourceField]: '',
    [commentField]: '',
  });

  return {
    sourceConfigJson: JSON.stringify(sourceConfig, null, 2),
    profile,
    itemKey,
    stringIds: [itemKey],
    appended: true,
    warnings: Array.from(new Set(warnings)),
  };
}

function appendFormatJsMapEntry(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
  warnings: string[],
): StatsigSourceConfigAppendEntryResult {
  const sourceField = profile.sourceField || 'defaultMessage';
  const commentField = profile.commentField || 'description';
  const messageMapMatch = writableFormatJsMessageMaps(sourceConfig, profile, warnings)[0];
  const messageMap = messageMapMatch.value;
  if (hasJsonPathWildcard(profile.collectionKey) && messageMapMatch.path) {
    warnings.push(`Added entry under "${messageMapMatch.path}".`);
  }
  let index = Object.keys(messageMap).length + 1;
  let itemKey = `message.${index}`;
  while (messageMap[itemKey] != null) {
    index += 1;
    itemKey = `message.${index}`;
  }

  messageMap[itemKey] = {
    [sourceField]: '',
    [commentField]: '',
  };

  return {
    sourceConfigJson: JSON.stringify(sourceConfig, null, 2),
    profile,
    itemKey,
    stringIds: [itemKey],
    appended: true,
    warnings: Array.from(new Set(warnings)),
  };
}

function appendFormatJsMultilingualMapEntry(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
  warnings: string[],
): StatsigSourceConfigAppendEntryResult {
  const sourceField = profile.sourceField || 'defaultMessage';
  const commentField = profile.commentField || 'description';
  const translationsField = profile.translationsField || 'translations';
  const messageMapMatch = writableFormatJsMessageMaps(sourceConfig, profile, warnings)[0];
  const messageMap = messageMapMatch.value;
  if (hasJsonPathWildcard(profile.collectionKey) && messageMapMatch.path) {
    warnings.push(`Added entry under "${messageMapMatch.path}".`);
  }
  let index = Object.keys(messageMap).length + 1;
  let itemKey = `message.${index}`;
  while (messageMap[itemKey] != null) {
    index += 1;
    itemKey = `message.${index}`;
  }

  messageMap[itemKey] = {
    [sourceField]: '',
    [commentField]: '',
    [translationsField]: {},
  };

  return {
    sourceConfigJson: JSON.stringify(sourceConfig, null, 2),
    profile,
    itemKey,
    stringIds: [itemKey],
    appended: true,
    warnings: Array.from(new Set(warnings)),
  };
}

export function normalizeOutputLocaleMapping(
  mojitoLocaleTags: string[],
  outputLocaleByMojitoLocale: OutputLocaleMapping,
): OutputLocaleMapping {
  return Object.fromEntries(
    mojitoLocaleTags.map((localeTag) => [
      localeTag,
      getMappedLocaleTag(localeTag, outputLocaleByMojitoLocale),
    ]),
  );
}

export function parseOutputLocaleMappingSpec(
  mappingText: string,
  mojitoLocaleTags: string[],
): OutputLocaleMappingParseResult {
  const spec = stripLocaleMappingFlag(mappingText);
  if (!spec) {
    return { mapping: {}, warnings: [] };
  }

  const mojitoLocaleByLowercase = new Map(
    mojitoLocaleTags.map((localeTag) => [localeTag.toLowerCase(), localeTag]),
  );
  const mapping: OutputLocaleMapping = {};
  const warnings: string[] = [];

  spec
    .split(/[,\n]+/)
    .map((entry) => entry.trim())
    .filter(Boolean)
    .forEach((entry) => {
      const parts = entry.split(':').map((part) => part.trim());
      if (parts.length !== 2 || !parts[0] || !parts[1]) {
        warnings.push(`Skipped invalid locale mapping "${entry}".`);
        return;
      }

      const [left, right] = parts;
      const mojitoLocale = mojitoLocaleByLowercase.get(right.toLowerCase());
      if (mojitoLocale) {
        mapping[mojitoLocale] = left;
        return;
      }

      const reversedMojitoLocale = mojitoLocaleByLowercase.get(left.toLowerCase());
      if (reversedMojitoLocale) {
        mapping[reversedMojitoLocale] = right;
        warnings.push(`Interpreted "${entry}" as Mojito-to-output locale mapping.`);
        return;
      }

      warnings.push(`Skipped "${entry}" because neither side matches a Mojito target locale.`);
    });

  return { mapping, warnings };
}

export function normalizeSourceStringId(value: string): string {
  return value
    .trim()
    .replace(/\s+/g, '.')
    .replace(/[^A-Za-z0-9._-]+/g, '_')
    .replace(/([_-])\.+/g, '$1')
    .replace(/\.([_-])/g, '$1')
    .replace(/\.{2,}/g, '.')
    .replace(/_{2,}/g, '_')
    .replace(/^[._-]+|[._-]+$/g, '');
}

export function detectStatsigSourceConfigProfile(schema: JsonRecord): {
  profile: StatsigSourceConfigProfile;
  warnings: string[];
} {
  const warnings: string[] = [];
  const topLevelProperties = getSchemaProperties(schema);
  const collectionEntry = Object.entries(topLevelProperties).find(([, propertySchema]) =>
    isArraySchema(propertySchema),
  );

  if (!collectionEntry) {
    throw new Error('Schema must define at least one top-level array collection.');
  }

  const [collectionKey, collectionSchema] = collectionEntry;
  const itemSchema = isJsonRecord(collectionSchema) ? collectionSchema.items : undefined;
  if (!isJsonRecord(itemSchema)) {
    throw new Error(`Schema collection "${collectionKey}" must define object items.`);
  }

  const itemProperties = getSchemaProperties(itemSchema);
  const translationsEntry =
    Object.entries(itemProperties).find(([key]) => key === 'translations') ??
    Object.entries(itemProperties).find(([, propertySchema]) =>
      looksLikeTranslationsSchema(propertySchema),
    );

  if (!translationsEntry) {
    throw new Error(`Schema collection "${collectionKey}" must define a translations object.`);
  }

  const [translationsField, translationsSchema] = translationsEntry;
  const sourceLocaleTag = detectSourceLocaleTag(translationsSchema);
  const sourceLocaleSchema = getLocaleSchema(translationsSchema, sourceLocaleTag);
  const translatableFields = detectTranslatableFields(sourceLocaleSchema);
  const itemIdField = detectItemIdField(itemProperties, translationsField, warnings);

  if (!translatableFields.length) {
    throw new Error(`Schema locale "${sourceLocaleTag}" must define string fields to translate.`);
  }
  if (sourceLocaleTag === 'en-US' && !hasLocaleSchema(translationsSchema, 'en-US')) {
    warnings.push('No explicit en-US schema found; defaulted source locale to en-US.');
  }

  return {
    profile: {
      collectionKey,
      itemIdField,
      translationsField,
      sourceLocaleTag,
      translatableFields,
    },
    warnings,
  };
}

function tryDetectStatsigProfile(schemaText: string): {
  profile: StatsigSourceConfigProfile;
  warnings: string[];
} {
  if (!schemaText.trim()) {
    return {
      profile: DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      warnings: [],
    };
  }

  try {
    return detectStatsigSourceConfigProfile(parseJsonRecord(schemaText, 'schema'));
  } catch (error) {
    return {
      profile: DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      warnings: [
        `Schema not used for detection: ${
          error instanceof Error ? error.message : 'Unable to parse schema.'
        }`,
      ],
    };
  }
}

function tryDetectStatsigProfileForFormat(
  schemaText: string,
  profileOverride?: Partial<StatsigSourceConfigProfile>,
): {
  profile: StatsigSourceConfigProfile;
  warnings: string[];
} {
  if (normalizeSourceFormat(profileOverride?.format) !== 'EMBEDDED_TRANSLATIONS') {
    return {
      profile: DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      warnings: [],
    };
  }

  return tryDetectStatsigProfile(schemaText);
}

export function parseJsonRecord(text: string, label: string): JsonRecord {
  const trimmed = text.trim();
  if (!trimmed) {
    throw new Error(`Paste a ${label} JSON object first.`);
  }

  const parsed = parseJsonWithTrailingCommas(trimmed, label);
  if (!isJsonRecord(parsed) || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object.`);
  }
  return parsed;
}

function normalizeStatsigProfile(profile: StatsigSourceConfigProfile): StatsigSourceConfigProfile {
  const format = normalizeSourceFormat(profile.format);
  const normalizedProfile = {
    format,
    collectionKey: profile.collectionKey.trim(),
    itemIdField: profile.itemIdField.trim(),
    translationsField: profile.translationsField.trim(),
    sourceLocaleTag: profile.sourceLocaleTag.trim(),
    translatableFields: profile.translatableFields.map((field) => field.trim()).filter(Boolean),
    sourceField: (profile.sourceField ?? DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE.sourceField)?.trim(),
    commentField: (
      profile.commentField ?? DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE.commentField
    )?.trim(),
  };

  if (format === 'FORMATJS_MAP' || format === 'FORMATJS_MULTILINGUAL_MAP') {
    if (!normalizedProfile.sourceField) {
      throw new Error('Provide a source field.');
    }
    if (format === 'FORMATJS_MULTILINGUAL_MAP' && !normalizedProfile.translationsField) {
      throw new Error('Provide a translations field.');
    }
    return normalizedProfile;
  }

  if (!normalizedProfile.collectionKey) {
    throw new Error('Provide a collection key.');
  }
  if (!normalizedProfile.itemIdField) {
    throw new Error('Provide an item id field.');
  }

  if (format === 'FLAT_SOURCE_ARRAY') {
    if (!normalizedProfile.sourceField) {
      throw new Error('Provide a source field.');
    }
    return normalizedProfile;
  }

  if (!normalizedProfile.translationsField) {
    throw new Error('Provide a translations field.');
  }
  if (!normalizedProfile.sourceLocaleTag) {
    throw new Error('Provide a source locale.');
  }
  if (!normalizedProfile.translatableFields.length) {
    throw new Error('Provide at least one translatable field.');
  }

  return normalizedProfile;
}

function normalizeSourceFormat(format: string | undefined): JsonConfigSourceFormat {
  if (
    format === 'FLAT_SOURCE_ARRAY' ||
    format === 'FORMATJS_MAP' ||
    format === 'FORMATJS_MULTILINGUAL_MAP'
  ) {
    return format;
  }
  return 'EMBEDDED_TRANSLATIONS';
}

function inferStatsigProfileFromSource(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
): StatsigSourceConfigProfile {
  if (isFormatJsSourceFormat(profile.format)) {
    return {
      ...profile,
      collectionKey: profile.collectionKey.trim(),
      itemIdField: profile.itemIdField || 'id',
      translationsField: profile.translationsField || 'translations',
      sourceLocaleTag: profile.sourceLocaleTag || 'en-US',
      translatableFields: [],
      sourceField: profile.sourceField || 'defaultMessage',
      commentField: profile.commentField || 'description',
    };
  }

  const collectionKey = profile.collectionKey.trim() || detectCollectionKeyFromSource(sourceConfig);
  const collectionValue = collectionKey ? sourceConfig[collectionKey] : undefined;
  const collection = Array.isArray(collectionValue) ? collectionValue : [];
  const sampleItem = collection.find(isJsonRecord);

  if (profile.format !== 'FLAT_SOURCE_ARRAY') {
    const formatJsDetection = detectFormatJsMessageMap(sourceConfig, profile);
    if (
      formatJsDetection &&
      (!Array.isArray(collectionValue) || !collectionLooksEmbeddedTranslatable(collection, profile))
    ) {
      return {
        ...profile,
        format: 'FORMATJS_MAP',
        collectionKey: formatJsDetection.collectionKey,
        itemIdField: profile.itemIdField || 'id',
        translationsField: profile.translationsField || 'translations',
        sourceLocaleTag: profile.sourceLocaleTag || 'en-US',
        translatableFields: [],
        sourceField: formatJsDetection.sourceField,
        commentField: profile.commentField || 'description',
      };
    }
  }

  if (profile.format === 'FLAT_SOURCE_ARRAY') {
    return {
      ...profile,
      collectionKey,
      itemIdField: profile.itemIdField || 'id',
      translationsField: profile.translationsField || 'translations',
      sourceLocaleTag: profile.sourceLocaleTag || 'en-US',
      translatableFields: [],
      sourceField: profile.sourceField || detectSourceFieldFromFlatItem(sampleItem) || 'source',
      commentField: profile.commentField || 'description',
    };
  }

  const flatSourceField = detectSourceFieldFromFlatItem(sampleItem);
  if (
    flatSourceField &&
    Array.isArray(collectionValue) &&
    !collectionLooksEmbeddedTranslatable(collection, profile)
  ) {
    return {
      ...profile,
      format: 'FLAT_SOURCE_ARRAY',
      collectionKey,
      itemIdField: profile.itemIdField || 'id',
      translationsField: profile.translationsField || 'translations',
      sourceLocaleTag: profile.sourceLocaleTag || 'en-US',
      translatableFields: [],
      sourceField: profile.sourceField || flatSourceField,
      commentField: profile.commentField || 'description',
    };
  }

  const translationsField =
    sampleItem && !isJsonRecord(sampleItem[profile.translationsField])
      ? (detectTranslationsFieldFromSource(sampleItem) ?? profile.translationsField)
      : profile.translationsField;
  const translations = sampleItem ? sampleItem[translationsField] : undefined;
  const sourceLocaleTag =
    isJsonRecord(translations) && !isJsonRecord(translations[profile.sourceLocaleTag])
      ? (detectSourceLocaleTagFromSource(translations) ?? profile.sourceLocaleTag)
      : profile.sourceLocaleTag;
  const sourceLocale = isJsonRecord(translations) ? translations[sourceLocaleTag] : undefined;
  const translatableFields = profile.translatableFields.length
    ? profile.translatableFields
    : detectTranslatableFieldsFromSource(sourceLocale);

  return {
    collectionKey,
    itemIdField: profile.itemIdField,
    translationsField,
    sourceLocaleTag,
    translatableFields,
    sourceField: profile.sourceField || 'source',
    commentField: profile.commentField || 'description',
  };
}

function getFormatJsMessageMaps(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
): JsonRecordPathMatch[] {
  if (!profile.collectionKey.trim()) {
    return [{ path: '', value: sourceConfig }];
  }
  const matches = getJsonRecordMatchesAtPath(sourceConfig, profile.collectionKey);
  if (matches.length) {
    return matches;
  }
  throw new Error(
    `Config must contain at least one FormatJS message map object at "${profile.collectionKey}".`,
  );
}

function writableFormatJsMessageMaps(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
  warnings: string[] = [],
): JsonRecordPathMatch[] {
  if (!profile.collectionKey.trim()) {
    return [{ path: '', value: sourceConfig }];
  }
  if (!hasJsonPathWildcard(profile.collectionKey)) {
    return [
      {
        path: profile.collectionKey.trim(),
        value: ensureJsonRecordAtPath(sourceConfig, profile.collectionKey),
      },
    ];
  }

  const matches = getJsonRecordMatchesAtPath(sourceConfig, profile.collectionKey);
  if (!matches.length) {
    throw new Error(
      `Config must contain at least one FormatJS message map object at "${profile.collectionKey}".`,
    );
  }
  if (matches.length > 1) {
    warnings.push(
      `Message map key "${profile.collectionKey}" matched ${matches.length} maps; new entries use "${matches[0].path}".`,
    );
  }
  return matches;
}

function writableFormatJsMessageMapForString(
  messageMapMatches: JsonRecordPathMatch[],
  stringId: string,
  profile: StatsigSourceConfigProfile,
  warnings: string[],
): JsonRecordPathMatch {
  const existingMatch = messageMapMatches.find((match) => match.value[stringId] != null);
  if (existingMatch) {
    return existingMatch;
  }

  const fallbackMatch = messageMapMatches[0];
  if (hasJsonPathWildcard(profile.collectionKey)) {
    warnings.push(
      `String "${stringId}" was not present in any map matched by "${profile.collectionKey}"; added it under "${fallbackMatch.path}".`,
    );
  }
  return fallbackMatch;
}

function detectFormatJsMessageMap(
  sourceConfig: JsonRecord,
  profile: StatsigSourceConfigProfile,
): { collectionKey: string; sourceField: string } | null {
  const collectionKey = profile.collectionKey.trim();
  for (const sourceField of formatJsSourceFieldCandidates(profile.sourceField)) {
    if (collectionKey) {
      const nestedMessageMaps = getJsonRecordMatchesAtPath(sourceConfig, collectionKey);
      if (
        nestedMessageMaps.some((match) =>
          recordLooksLikeFormatJsMessageMap(match.value, sourceField),
        )
      ) {
        return { collectionKey, sourceField };
      }
    }
    if (recordLooksLikeFormatJsMessageMap(sourceConfig, sourceField)) {
      return { collectionKey: '', sourceField };
    }
  }
  return null;
}

function formatJsSourceFieldCandidates(sourceField: string | undefined): string[] {
  const fields = ['defaultMessage', sourceField ?? '', 'source']
    .map((field) => field.trim())
    .filter((field) => field.length > 0);
  return Array.from(new Set(fields));
}

function recordLooksLikeFormatJsMessageMap(sourceConfig: JsonRecord, sourceField: string): boolean {
  return Object.values(sourceConfig).some(
    (value) => isJsonRecord(value) && typeof value[sourceField] === 'string',
  );
}

function isFormatJsSourceFormat(
  format: JsonConfigSourceFormat | undefined,
): format is 'FORMATJS_MAP' | 'FORMATJS_MULTILINGUAL_MAP' {
  return format === 'FORMATJS_MAP' || format === 'FORMATJS_MULTILINGUAL_MAP';
}

function collectionLooksEmbeddedTranslatable(
  collection: JsonValue[],
  profile: StatsigSourceConfigProfile,
): boolean {
  const sampleItem = collection.find(isJsonRecord);
  if (!sampleItem) {
    return false;
  }
  const translationsField =
    detectTranslationsFieldFromSource(sampleItem) ?? profile.translationsField;
  const translations = sampleItem[translationsField];
  return isJsonRecord(translations) && Boolean(detectSourceLocaleTagFromSource(translations));
}

function detectSourceFieldFromFlatItem(item: JsonRecord | undefined): string | null {
  if (!item) {
    return null;
  }
  for (const preferredField of ['source', 'defaultMessage', 'message', 'text']) {
    if (typeof item[preferredField] === 'string') {
      return preferredField;
    }
  }
  return (
    Object.entries(item).find(
      ([field, value]) =>
        typeof value === 'string' &&
        !['id', 'key', 'name', 'description', 'comment'].includes(field),
    )?.[0] ?? null
  );
}

function detectCollectionKeyFromSource(sourceConfig: JsonRecord): string {
  const arrayEntries = Object.entries(sourceConfig).filter(([, value]) => Array.isArray(value));
  const translatableEntry = arrayEntries.find(([, value]) => {
    const collection = value as JsonValue[];
    const sampleItem = collection.find(isJsonRecord);
    return sampleItem ? Boolean(detectTranslationsFieldFromSource(sampleItem)) : false;
  });
  return translatableEntry?.[0] ?? arrayEntries[0]?.[0] ?? '';
}

function detectTranslationsFieldFromSource(item: JsonRecord): string | null {
  const preferredTranslations = item.translations;
  if (
    isJsonRecord(preferredTranslations) &&
    detectSourceLocaleTagFromSource(preferredTranslations)
  ) {
    return 'translations';
  }
  return (
    Object.entries(item).find(([, value]) =>
      isJsonRecord(value) ? Boolean(detectSourceLocaleTagFromSource(value)) : false,
    )?.[0] ?? null
  );
}

function detectSourceLocaleTagFromSource(translations: JsonRecord): string | null {
  if (isJsonRecord(translations['en-US'])) {
    return 'en-US';
  }
  return Object.keys(translations).find(isLocaleLikeKey) ?? null;
}

function detectTranslatableFieldsFromSource(value: JsonValue | undefined): string[] {
  if (!isJsonRecord(value)) {
    return [];
  }
  return Object.entries(value)
    .filter(([, fieldValue]) => typeof fieldValue === 'string')
    .map(([field]) => field);
}

function resolveStatsigItemKey(
  item: JsonRecord,
  index: number,
  profile: StatsigSourceConfigProfile,
  warnings: string[],
  warnOnMissingItemId: boolean,
): string {
  const rawItemId = item[profile.itemIdField];
  if (typeof rawItemId === 'string' && rawItemId.trim()) {
    return rawItemId.trim();
  }
  if (typeof rawItemId === 'number' && Number.isFinite(rawItemId)) {
    return String(rawItemId);
  }

  const fallback = `${profile.collectionKey}.${index}`;
  if (warnOnMissingItemId) {
    warnings.push(
      `${profile.collectionKey}[${index}] is missing "${profile.itemIdField}"; using index-based string ids under "${fallback}".`,
    );
  }
  return fallback;
}

function shouldWarnOnMissingItemId(
  schemaText: string,
  collection: JsonValue[],
  profile: StatsigSourceConfigProfile,
): boolean {
  if (collectionHasUsableItemId(collection, profile.itemIdField)) {
    return true;
  }
  return schemaDefinesItemField(schemaText, profile.collectionKey, profile.itemIdField);
}

function collectionHasUsableItemId(collection: JsonValue[], itemIdField: string): boolean {
  return collection.some((item) => {
    if (!isJsonRecord(item)) {
      return false;
    }
    const rawItemId = item[itemIdField];
    return (
      (typeof rawItemId === 'string' && Boolean(rawItemId.trim())) ||
      (typeof rawItemId === 'number' && Number.isFinite(rawItemId))
    );
  });
}

function schemaDefinesItemField(
  schemaText: string,
  collectionKey: string,
  itemField: string,
): boolean {
  if (!schemaText.trim()) {
    return false;
  }

  try {
    const schema = parseJsonRecord(schemaText, 'schema');
    const collectionSchema = getSchemaProperties(schema)[collectionKey];
    const itemSchema = isJsonRecord(collectionSchema) ? collectionSchema.items : undefined;
    if (!isJsonRecord(itemSchema)) {
      return false;
    }
    return Boolean(getSchemaProperties(itemSchema)[itemField]);
  } catch {
    return false;
  }
}

function detectItemIdField(
  itemProperties: Record<string, JsonValue>,
  translationsField: string,
  warnings: string[],
): string {
  if (isStringSchema(itemProperties.id)) {
    return 'id';
  }

  const stringField = Object.entries(itemProperties).find(
    ([field, propertySchema]) => field !== translationsField && isStringSchema(propertySchema),
  );
  if (!stringField) {
    warnings.push(
      'No stable item id field was found in the schema. Mojito will use "id" when present and fall back to array indexes for items without id. Add an id field to keep string ids stable when entries are reordered.',
    );
    return 'id';
  }
  return stringField[0];
}

function shouldAppendItemId(schemaText: string, profile: StatsigSourceConfigProfile): boolean {
  if (profile.format !== 'EMBEDDED_TRANSLATIONS') {
    return true;
  }

  if (!schemaText.trim()) {
    return false;
  }

  try {
    const schema = parseJsonRecord(schemaText, 'schema');
    const collectionSchema = getSchemaProperties(schema)[profile.collectionKey];
    const itemSchema = isJsonRecord(collectionSchema) ? collectionSchema.items : undefined;
    if (!isJsonRecord(itemSchema)) {
      return false;
    }
    const itemProperties = getSchemaProperties(itemSchema);
    if (itemProperties[profile.itemIdField]) {
      return true;
    }
    return false;
  } catch {
    return false;
  }
}

function nextStatsigItemId(collection: JsonValue[], itemIdField: string): string {
  const usedIds = new Set(
    collection.flatMap((item): string[] => {
      if (!isJsonRecord(item)) {
        return [];
      }
      const value = item[itemIdField];
      if (typeof value === 'string' && value.trim()) {
        return [value.trim()];
      }
      if (typeof value === 'number' && Number.isFinite(value)) {
        return [String(value)];
      }
      return [];
    }),
  );

  let index = collection.length + 1;
  let itemId = `item_${index}`;
  while (usedIds.has(itemId)) {
    index += 1;
    itemId = `item_${index}`;
  }
  return itemId;
}

function fallbackItemKey(profile: StatsigSourceConfigProfile, itemIndex: number): string {
  return `${profile.collectionKey}.${itemIndex}`;
}

function detectSourceLocaleTag(translationsSchema: JsonValue): string {
  if (!isJsonRecord(translationsSchema)) {
    return 'en-US';
  }

  const properties = getSchemaProperties(translationsSchema);
  if (properties['en-US']) {
    return 'en-US';
  }

  const localeProperty = Object.keys(properties).find(isLocaleLikeKey);
  return localeProperty ?? 'en-US';
}

function getLocaleSchema(translationsSchema: JsonValue, localeTag: string): JsonValue {
  if (!isJsonRecord(translationsSchema)) {
    return {};
  }

  const properties = getSchemaProperties(translationsSchema);
  if (properties[localeTag]) {
    return properties[localeTag];
  }

  const patternProperties = translationsSchema.patternProperties;
  if (isJsonRecord(patternProperties)) {
    const firstPatternSchema = Object.values(patternProperties).find(isJsonRecord);
    if (firstPatternSchema) {
      return firstPatternSchema;
    }
  }

  return {};
}

function detectTranslatableFields(localeSchema: JsonValue): string[] {
  const properties = getSchemaProperties(localeSchema);
  return Object.entries(properties)
    .filter(([, propertySchema]) => isStringSchema(propertySchema))
    .map(([field]) => field);
}

function hasLocaleSchema(translationsSchema: JsonValue, localeTag: string): boolean {
  return (
    isJsonRecord(translationsSchema) && Boolean(getSchemaProperties(translationsSchema)[localeTag])
  );
}

function looksLikeTranslationsSchema(schema: JsonValue): boolean {
  if (!isJsonRecord(schema)) {
    return false;
  }

  const properties = getSchemaProperties(schema);
  return (
    Object.keys(properties).some(isLocaleLikeKey) ||
    (isJsonRecord(schema.patternProperties) && Object.keys(schema.patternProperties).length > 0)
  );
}

function getSchemaProperties(schema: JsonValue): Record<string, JsonValue> {
  if (!isJsonRecord(schema) || !isJsonRecord(schema.properties)) {
    return {};
  }
  return schema.properties;
}

function isArraySchema(schema: JsonValue): boolean {
  return isJsonRecord(schema) && schema.type === 'array';
}

function isStringSchema(schema: JsonValue): boolean {
  if (!isJsonRecord(schema)) {
    return false;
  }
  return schema.type === 'string' || (Array.isArray(schema.type) && schema.type.includes('string'));
}

function isLocaleLikeKey(value: string): boolean {
  return /^[a-z]{2,3}(?:-[A-Z][A-Za-z0-9]{1,8})?$/.test(value);
}

function getTranslatedTarget(
  sourceString: JsonConfigLocalizationDraftString,
  localeTag: string,
  readinessByString: Record<string, JsonConfigLocalizationStringReadiness>,
): string {
  return (
    readinessByString[sourceString.clientId]?.locales.find(
      (locale) => locale.localeTag === localeTag,
    )?.target ?? ''
  );
}

function getMappedLocaleTag(
  mojitoLocaleTag: string,
  outputLocaleByMojitoLocale: OutputLocaleMapping,
): string {
  return outputLocaleByMojitoLocale[mojitoLocaleTag]?.trim() || mojitoLocaleTag;
}

function stripLocaleMappingFlag(mappingText: string): string {
  let spec = mappingText.trim();
  spec = spec.replace(/^-lm(?:=|\s+)?/, '').trim();

  const first = spec[0];
  const last = spec[spec.length - 1];
  if ((first === "'" && last === "'") || (first === '"' && last === '"')) {
    return spec.slice(1, -1).trim();
  }

  return spec;
}

function parseJsonWithTrailingCommas(text: string, label: string): unknown {
  try {
    return JSON.parse(text);
  } catch (error) {
    const strictError = error instanceof Error ? error.message : 'Unable to parse JSON.';
    const withoutTrailingCommas = stripJsonTrailingCommas(text);
    if (withoutTrailingCommas !== text) {
      try {
        return JSON.parse(withoutTrailingCommas);
      } catch {
        // Keep the strict parser error because it points to the original pasted text.
      }
    }
    throw new Error(`Invalid ${label} JSON: ${strictError}`);
  }
}

function stripJsonTrailingCommas(text: string): string {
  let output = '';
  let inString = false;
  let escaped = false;

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];

    if (inString) {
      output += char;
      if (escaped) {
        escaped = false;
      } else if (char === '\\') {
        escaped = true;
      } else if (char === '"') {
        inString = false;
      }
      continue;
    }

    if (char === '"') {
      inString = true;
      output += char;
      continue;
    }

    if (char === ',') {
      const nextMeaningfulChar = text.slice(index + 1).match(/\S/)?.[0];
      if (nextMeaningfulChar === '}' || nextMeaningfulChar === ']') {
        continue;
      }
    }

    output += char;
  }

  return output;
}

function cloneJsonRecord(value: JsonRecord): JsonRecord {
  return JSON.parse(JSON.stringify(value)) as JsonRecord;
}

function ensureJsonRecord(parent: JsonRecord, key: string): JsonRecord {
  if (!isJsonRecord(parent[key])) {
    parent[key] = {};
  }
  return parent[key];
}

function getJsonRecordMatchesAtPath(parent: JsonRecord, path: string): JsonRecordPathMatch[] {
  const normalizedPath = path.trim();
  if (!normalizedPath) {
    return [{ path: '', value: parent }];
  }

  if (!hasJsonPathWildcard(normalizedPath)) {
    const exactValue = parent[normalizedPath];
    if (isJsonRecord(exactValue)) {
      return [{ path: normalizedPath, value: exactValue }];
    }
  }

  const matches: JsonRecordPathMatch[] = [];
  collectJsonRecordMatchesAtPath(parent, jsonPathSegments(normalizedPath), 0, '', matches);
  return matches;
}

function collectJsonRecordMatchesAtPath(
  current: JsonValue,
  segments: string[],
  segmentIndex: number,
  currentPath: string,
  matches: JsonRecordPathMatch[],
) {
  if (segmentIndex >= segments.length) {
    if (isJsonRecord(current)) {
      matches.push({ path: currentPath, value: current });
    }
    return;
  }

  if (!isJsonRecord(current)) {
    return;
  }

  const segment = segments[segmentIndex];
  if (segment === '**') {
    collectJsonRecordMatchesAtPath(current, segments, segmentIndex + 1, currentPath, matches);
    Object.entries(current).forEach(([childKey, childValue]) => {
      collectJsonRecordMatchesAtPath(
        childValue,
        segments,
        segmentIndex,
        formatJsonPath(currentPath, childKey),
        matches,
      );
    });
    return;
  }

  if (segment === '*') {
    Object.entries(current).forEach(([childKey, childValue]) => {
      collectJsonRecordMatchesAtPath(
        childValue,
        segments,
        segmentIndex + 1,
        formatJsonPath(currentPath, childKey),
        matches,
      );
    });
    return;
  }

  collectJsonRecordMatchesAtPath(
    current[segment],
    segments,
    segmentIndex + 1,
    formatJsonPath(currentPath, segment),
    matches,
  );
}

function ensureJsonRecordAtPath(parent: JsonRecord, path: string): JsonRecord {
  const normalizedPath = path.trim();
  if (!normalizedPath) {
    return parent;
  }

  const exactValue = parent[normalizedPath];
  if (isJsonRecord(exactValue)) {
    return exactValue;
  }

  const segments = normalizedPath
    .split('.')
    .map((part) => part.trim())
    .filter(Boolean);
  if (!segments.length) {
    return parent;
  }
  if (segments.length === 1) {
    return ensureJsonRecord(parent, segments[0]);
  }

  let current = parent;
  segments.forEach((segment) => {
    current = ensureJsonRecord(current, segment);
  });
  return current;
}

function hasJsonPathWildcard(path: string): boolean {
  return jsonPathSegments(path).some((segment) => segment === '*' || segment === '**');
}

function jsonPathSegments(path: string): string[] {
  return path
    .split('.')
    .map((part) => part.trim())
    .filter(Boolean);
}

function formatJsonPath(parentPath: string, childKey: string): string {
  return parentPath ? `${parentPath}.${childKey}` : childKey;
}

function clearTranslatableFields(record: JsonRecord, fields: string[]) {
  fields.forEach((field) => {
    delete record[field];
  });
}

function translatableTextFields(record: JsonRecord, fields: string[]): Record<string, string> {
  return Object.fromEntries(
    fields.flatMap((field): [string, string][] => {
      const value = record[field];
      return typeof value === 'string' ? [[field, value]] : [];
    }),
  );
}

function hasNonTranslatableFields(record: JsonRecord, fields: string[]): boolean {
  const fieldSet = new Set(fields);
  return Object.keys(record).some((key) => !fieldSet.has(key));
}

function isJsonRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function buildJsonConfigLocalizationFilename(repositoryName: string): string {
  const normalizedName = normalizeSourceStringId(repositoryName).toLowerCase() || 'repository';
  return `${normalizedName}-json-config-localization.json`;
}

export function buildJsonConfigLocalizationLocaleFilesFilename(repositoryName: string): string {
  const normalizedName = normalizeSourceStringId(repositoryName).toLowerCase() || 'repository';
  return `${normalizedName}-locale-files.zip`;
}

function toSafeLocaleFilename(localeTag: string): string {
  return localeTag.trim().replace(/[^A-Za-z0-9._-]+/g, '_') || 'locale';
}

function buildLocaleReadiness(
  tmTextUnitId: number | null | undefined,
  localeTag: string,
  targetByTextUnitAndLocale: Map<string, ApiTextUnit>,
): JsonConfigLocalizationLocaleReadiness {
  const targetRow =
    tmTextUnitId == null
      ? undefined
      : targetByTextUnitAndLocale.get(buildTargetKey(tmTextUnitId, localeTag));
  const target = targetRow?.target ?? '';
  const translated = target.length > 0;
  const status = getReadinessStatus(
    targetRow?.status,
    targetRow?.includedInLocalizedFile,
    translated,
  );

  return {
    localeTag,
    target,
    status,
    translated,
    reviewed: status === 'APPROVED',
  };
}

function getReadinessStatus(
  status: ApiTextUnitStatus | null | undefined,
  includedInLocalizedFile: boolean | undefined,
  translated: boolean,
): JsonConfigLocalizationReadinessStatus {
  if (!translated) {
    return 'MISSING';
  }
  if (includedInLocalizedFile === false) {
    return 'EXCLUDED';
  }
  if (status === 'APPROVED') {
    return 'APPROVED';
  }
  if (status === 'REVIEW_NEEDED') {
    return 'REVIEW_NEEDED';
  }
  return 'TRANSLATION_NEEDED';
}

function buildExistingClientId(tmTextUnitId: number): string {
  return `text-unit-${tmTextUnitId}`;
}

function buildTargetKey(tmTextUnitId: number, localeTag: string): string {
  return `${tmTextUnitId}:${localeTag}`;
}
