import type { ApiInflectionBindingManifest } from '../api/glossaries';

const TERM_EXPRESSION_PATTERN = /\{\$(?<argument>[A-Za-z_][\w.-]*)\s+:term(?<options>[^{}]*)\}/gu;
const VARIABLE_REFERENCE_PATTERN = /^\$[A-Za-z_][\w.-]*$/u;
const MF2_BINDING_MESSAGE_ID_SUFFIX_PATTERN = /^[a-z0-9._-]+$/u;

const SUPPORTED_OPTIONS = new Set([
  'article',
  'case',
  'count',
  'definiteness',
  'number',
  'preposition',
]);
const HINDI_PRONOUN_OPTIONS = new Set([
  'agreeWith',
  'agreeWithCount',
  'case',
  'count',
  'person',
  'register',
]);
const HINDI_PRONOUN_MARKER_OPTIONS = new Set(['agreeWith', 'agreeWithCount', 'person', 'register']);
const HINDI_PRONOUN_PERSON_VALUES = new Set(['first', 'second', 'third']);
const HINDI_PRONOUN_CASE_VALUES = new Set(['accusative', 'direct', 'ergative', 'genitive']);
const HINDI_PRONOUN_REGISTER_VALUES = new Set(['formal', 'informal', 'intimate']);
const SUPPORTED_ARTICLES = new Set(['definite', 'indefinite']);
const SUPPORTED_CASES = new Set([
  'ablative',
  'accusative',
  'dative',
  'direct',
  'genitive',
  'instrumental',
  'locative',
  'nominative',
  'oblique',
  'prepositional',
  'sociative',
  'vocative',
]);
const SUPPORTED_DEFINITENESS = new Set(['construct', 'definite', 'indefinite']);
const SUPPORTED_NUMBERS = new Set(['dual', 'plural', 'singular']);
const SUPPORTED_PREPOSITIONS = new Set(['de', 'em', 'por']);
const TURKISH_SUFFIX_CASES = new Set(['ablative', 'accusative', 'dative', 'locative']);
const UNSUPPORTED_RUNTIME_TERM_INFLECTION_REQUIREMENT =
  'unsupported-locale-runtime-term-inflection';
const RUNTIME_TERM_INFLECTION_UNSUPPORTED_LOCALES = new Set([
  'en',
  'id',
  'ja',
  'ko',
  'ms',
  'nb',
  'nl',
  'th',
  'vi',
  'yue',
  'zh',
]);

export type Mf2TermUsageRequirement = {
  argument: string;
  bindingArguments: string[];
  end: number;
  expression: string;
  options: Record<string, string>;
  relatedArgument?: string;
  requirements: string[];
  start: number;
};

export type Mf2TermBindingTermIds = Record<string, readonly string[] | null | undefined>;

export type Mf2TermBindingManifestGroup = {
  label: string;
  message: string;
  messageIdSuffix?: string;
  termIdsByArgument?: Mf2TermBindingTermIds;
  usages: readonly Mf2TermUsageRequirement[];
};

export type BuildMf2TermBindingManifestOptions = {
  termIdsByArgument?: Mf2TermBindingTermIds;
  termIdsByMessageId?: Record<string, Mf2TermBindingTermIds | undefined>;
};

export function extractMf2TermRequirements(
  message: string,
  locale?: string | null,
): Mf2TermUsageRequirement[] {
  const usages: Mf2TermUsageRequirement[] = [];
  for (const match of message.matchAll(TERM_EXPRESSION_PATTERN)) {
    if (match.index == null || !match.groups) {
      continue;
    }
    const options = parseOptions(match.groups.options);
    usages.push({
      argument: match.groups.argument,
      bindingArguments: bindingArgumentsForTermOptions(match.groups.argument, options, locale),
      end: match.index + match[0].length,
      expression: match[0],
      options,
      relatedArgument: relatedArgumentForTermOptions(options, locale),
      requirements: requirementsForTermOptions(options, locale),
      start: match.index,
    });
  }
  return usages;
}

export function buildMf2TermBindingManifest(
  messageId: string,
  locale: string,
  groups: readonly Mf2TermBindingManifestGroup[],
  options: BuildMf2TermBindingManifestOptions = {},
): ApiInflectionBindingManifest {
  const normalizedMessageId = messageId.trim();
  const normalizedLocale = locale.trim();
  if (!normalizedMessageId) {
    throw new Error('MF2 term binding manifest message id must not be blank');
  }
  if (!normalizedLocale) {
    throw new Error('MF2 term binding manifest locale must not be blank');
  }

  const messages: Record<string, string> = {};
  const argumentTerms: Record<string, Record<string, string[]>> = {};
  for (const group of groups) {
    const groupMessageId = `${normalizedMessageId}.${normalizeMf2BindingMessageIdSuffix(group)}`;
    messages[groupMessageId] = group.message;
    argumentTerms[groupMessageId] = {};
    for (const argument of uniqueMf2TermArguments(group.usages)) {
      argumentTerms[groupMessageId][argument] = normalizeMf2TermIds(
        options.termIdsByMessageId?.[groupMessageId]?.[argument] ??
          group.termIdsByArgument?.[argument] ??
          options.termIdsByArgument?.[argument],
      );
    }
  }

  return {
    schema: 'mojito-mf2-inflection/message-term-binding-manifest/v0',
    locale: normalizedLocale,
    messages,
    argumentTerms,
  };
}

export function uniqueMf2TermArguments(usages: readonly Mf2TermUsageRequirement[]) {
  const argumentsInOrder: string[] = [];
  const seen = new Set<string>();
  for (const usage of usages) {
    const bindingArguments = usage.bindingArguments.length
      ? usage.bindingArguments
      : [usage.argument];
    for (const argument of bindingArguments) {
      if (!seen.has(argument)) {
        seen.add(argument);
        argumentsInOrder.push(argument);
      }
    }
  }
  return argumentsInOrder;
}

export function requirementsForTermOptions(
  options: Record<string, string>,
  locale?: string | null,
): string[] {
  if (isHindiPronounUsage(locale, options)) {
    return requirementsForHindiPronounOptions(options);
  }

  validateOptions(options);

  if (requiresUnsupportedRuntimeTermInflectionDiagnostic(locale, options)) {
    return [UNSUPPORTED_RUNTIME_TERM_INFLECTION_REQUIREMENT];
  }

  const requirements = ['partOfSpeech=noun'];
  if (!isLocale(locale, 'tr')) {
    requirements.push('gender');
  }
  requirements.push('number');

  const article = options.article;
  const grammaticalCase = options.case;
  const definiteness = options.definiteness;
  const explicitNumber = options.number;
  const preposition = options.preposition;
  const selectedNumbers = explicitNumber == null ? ['singular', 'plural'] : [explicitNumber];
  const turkishSuffixComposition = isTurkishSuffixComposition(locale, options);
  const spanishArticleComposition = isSpanishArticleComposition(locale, options);
  const italianArticleComposition = isItalianArticleComposition(locale, options);
  const portugueseArticleComposition = isPortugueseArticleComposition(locale, options);

  if (turkishSuffixComposition) {
    requirements.push(
      'turkishSuffix.vowelEnd',
      'turkishSuffix.frontVowel',
      'turkishSuffix.roundedVowel',
      'turkishSuffix.hardConsonant',
      'forms.bare.singular',
    );
  } else if (spanishArticleComposition) {
    requirements.push('stress', 'forms.bare.singular', 'forms.bare.plural');
  } else if (italianArticleComposition) {
    requirements.push('articleClass', 'forms.bare.singular', 'forms.bare.plural');
  } else if (portugueseArticleComposition) {
    requirements.push('forms.bare.singular', 'forms.bare.plural');
  } else if (preposition) {
    for (const number of selectedNumbers) {
      requirements.push(`forms.preposition.${preposition}.${article}.${number}`);
    }
  } else if (definiteness && grammaticalCase) {
    for (const number of selectedNumbers) {
      requirements.push(`forms.${definiteness}.${grammaticalCase}.${number}`);
    }
  } else if (definiteness) {
    for (const number of selectedNumbers) {
      requirements.push(`forms.${definiteness}.${number}`);
    }
  } else if (article && grammaticalCase) {
    for (const number of selectedNumbers) {
      requirements.push(`forms.${article}.${grammaticalCase}.${number}`);
    }
  } else if (article === 'definite' || article === 'indefinite') {
    requirements.push('elision');
    for (const number of selectedNumbers) {
      requirements.push(`forms.${article}.${number}`);
    }
  } else if (grammaticalCase) {
    for (const number of selectedNumbers) {
      requirements.push(`forms.${grammaticalCase}.${number}`);
    }
  } else if (explicitNumber) {
    requirements.push(`forms.bare.${explicitNumber}`);
  }

  if (
    options.count &&
    !spanishArticleComposition &&
    !italianArticleComposition &&
    !portugueseArticleComposition &&
    !turkishSuffixComposition
  ) {
    requirements.push('forms.count.one', 'forms.count.other');
  }

  if (
    !article &&
    !grammaticalCase &&
    !definiteness &&
    !explicitNumber &&
    !preposition &&
    !options.count
  ) {
    requirements.push('forms.bare.singular', 'forms.bare.plural');
  }

  return requirements;
}

export function mf2RuntimeVariableNamesForUsage(usage: Mf2TermUsageRequirement): string[] {
  const variables: string[] = [];
  const seen = new Set<string>();
  for (const [option, value] of Object.entries(usage.options)) {
    if (option === 'agreeWith') {
      continue;
    }
    const variableName = runtimeVariableName(value);
    if (variableName && !seen.has(variableName)) {
      seen.add(variableName);
      variables.push(variableName);
    }
  }
  return variables;
}

function requirementsForHindiPronounOptions(options: Record<string, string>): string[] {
  validateHindiPronounOptions(options);

  const requirements = ['hindiPronoun.person', 'hindiPronoun.case'];
  if (options.count) {
    requirements.push('hindiPronoun.number');
  }
  if (options.person === 'second' || options.register) {
    requirements.push('hindiPronoun.register');
  }
  if (options.case === 'genitive') {
    requirements.push('agreeWith.gender');
    requirements.push(options.agreeWithCount ? 'agreeWith.count' : 'agreeWith.number');
  }
  return requirements;
}

function bindingArgumentsForTermOptions(
  argument: string,
  options: Record<string, string>,
  locale?: string | null,
): string[] {
  if (!isHindiPronounUsage(locale, options)) {
    return [argument];
  }
  const relatedArgument = relatedArgumentForTermOptions(options, locale);
  return relatedArgument ? [relatedArgument] : [];
}

function relatedArgumentForTermOptions(
  options: Record<string, string>,
  locale?: string | null,
): string | undefined {
  if (!isHindiPronounUsage(locale, options) || !options.agreeWith) {
    return undefined;
  }
  return variableReferenceName(options.agreeWith, 'agreeWith', 'Hindi pronoun');
}

function normalizeMf2BindingMessageIdSuffix(group: Mf2TermBindingManifestGroup) {
  const suffix = (group.messageIdSuffix ?? group.label).trim().toLowerCase();
  if (!suffix) {
    throw new Error('MF2 term binding manifest group suffix must not be blank');
  }
  if (!MF2_BINDING_MESSAGE_ID_SUFFIX_PATTERN.test(suffix)) {
    throw new Error(`Unsupported MF2 term binding manifest group suffix: ${suffix}`);
  }
  return suffix;
}

function normalizeMf2TermIds(termIds: readonly string[] | null | undefined) {
  if (!termIds) {
    return [];
  }

  const normalizedTermIds: string[] = [];
  const seen = new Set<string>();
  for (const termId of termIds) {
    const normalizedTermId = termId.trim();
    if (!normalizedTermId) {
      throw new Error('MF2 term binding manifest term id must not be blank');
    }
    if (!seen.has(normalizedTermId)) {
      seen.add(normalizedTermId);
      normalizedTermIds.push(normalizedTermId);
    }
  }
  return normalizedTermIds;
}

function parseOptions(rawOptions: string): Record<string, string> {
  const options: Record<string, string> = {};
  for (const token of rawOptions.trim().split(/\s+/u)) {
    if (!token) {
      continue;
    }
    const equals = token.indexOf('=');
    const key = equals > 0 ? token.slice(0, equals) : token;
    const rawValue = equals > 0 ? token.slice(equals + 1) : 'true';
    const value =
      rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"')
        ? rawValue.slice(1, -1)
        : rawValue;
    if (!key) {
      throw new Error(`Invalid term option token: ${token}`);
    }
    if (Object.prototype.hasOwnProperty.call(options, key)) {
      throw new Error(`Duplicate term option: ${key}`);
    }
    options[key] = value;
  }
  return options;
}

function validateOptions(options: Record<string, string>) {
  for (const [key, value] of Object.entries(options)) {
    if (!SUPPORTED_OPTIONS.has(key)) {
      throw new Error(`Unsupported term option: ${key}`);
    }
    if (!value.trim()) {
      throw new Error(`Term option value must not be blank: ${key}`);
    }
  }

  if (options.article && !SUPPORTED_ARTICLES.has(options.article)) {
    throw new Error(`Unsupported article option: ${options.article}`);
  }
  if (options.case && !SUPPORTED_CASES.has(options.case)) {
    throw new Error(`Unsupported case option: ${options.case}`);
  }
  if (options.definiteness && !SUPPORTED_DEFINITENESS.has(options.definiteness)) {
    throw new Error(`Unsupported definiteness option: ${options.definiteness}`);
  }
  if (options.definiteness && options.article) {
    throw new Error('Definiteness option cannot be combined with article option');
  }
  if (options.preposition && !SUPPORTED_PREPOSITIONS.has(options.preposition)) {
    throw new Error(`Unsupported preposition option: ${options.preposition}`);
  }
  if (options.preposition && !options.article) {
    throw new Error('Preposition option requires article option');
  }
  if (options.preposition && options.case) {
    throw new Error('Preposition option cannot be combined with case option');
  }
  if (
    options.preposition &&
    !isPortuguesePrepositionArticleCombination(options.preposition, options.article)
  ) {
    throw new Error(
      `Unsupported preposition/article combination: ${options.preposition} + ${options.article}`,
    );
  }
  if (options.count && !VARIABLE_REFERENCE_PATTERN.test(options.count)) {
    throw new Error(`Count option must reference a variable: ${options.count}`);
  }
  if (options.number && !SUPPORTED_NUMBERS.has(options.number)) {
    throw new Error(`Unsupported number option: ${options.number}`);
  }
  if (options.number && options.count) {
    throw new Error('Number option cannot be combined with count option');
  }
}

function validateHindiPronounOptions(options: Record<string, string>) {
  for (const [key, value] of Object.entries(options)) {
    if (!HINDI_PRONOUN_OPTIONS.has(key)) {
      throw new Error(`Unsupported Hindi pronoun term option: ${key}`);
    }
    if (!value.trim()) {
      throw new Error(`Hindi pronoun term option value must not be blank: ${key}`);
    }
  }

  validateAllowedOption(options, 'person', HINDI_PRONOUN_PERSON_VALUES, 'Hindi pronoun');
  validateAllowedOption(options, 'case', HINDI_PRONOUN_CASE_VALUES, 'Hindi pronoun');
  validateAllowedOption(options, 'register', HINDI_PRONOUN_REGISTER_VALUES, 'Hindi pronoun');
  validateVariableReferenceOption(options, 'count', 'Hindi pronoun');
  validateVariableReferenceOption(options, 'agreeWith', 'Hindi pronoun');
  validateVariableReferenceOption(options, 'agreeWithCount', 'Hindi pronoun');
}

function validateAllowedOption(
  options: Record<string, string>,
  option: string,
  allowedValues: ReadonlySet<string>,
  label: string,
) {
  const value = options[option];
  if (value != null && !allowedValues.has(value)) {
    throw new Error(`Unsupported ${label} ${option} option: ${value}`);
  }
}

function validateVariableReferenceOption(
  options: Record<string, string>,
  option: string,
  label: string,
) {
  const value = options[option];
  if (value != null) {
    variableReferenceName(value, option, label);
  }
}

function variableReferenceName(value: string, option: string, label = 'Term') {
  if (!VARIABLE_REFERENCE_PATTERN.test(value)) {
    throw new Error(`${label} ${option} option must reference a variable: ${value}`);
  }
  return value.slice(1);
}

function isSpanishArticleComposition(
  locale: string | null | undefined,
  options: Record<string, string>,
) {
  return (
    isLocale(locale, 'es') &&
    !options.case &&
    !options.preposition &&
    (options.article === 'definite' || options.article === 'indefinite')
  );
}

function isItalianArticleComposition(
  locale: string | null | undefined,
  options: Record<string, string>,
) {
  return (
    isLocale(locale, 'it') &&
    !options.case &&
    !options.preposition &&
    (options.article === 'definite' || options.article === 'indefinite')
  );
}

function isPortugueseArticleComposition(
  locale: string | null | undefined,
  options: Record<string, string>,
) {
  return (
    isLocale(locale, 'pt') &&
    !options.case &&
    isPortuguesePrepositionArticleCombination(options.preposition, options.article)
  );
}

function isTurkishSuffixComposition(
  locale: string | null | undefined,
  options: Record<string, string>,
) {
  if (!isLocale(locale, 'tr') || options.article || options.preposition || options.definiteness) {
    return false;
  }
  const hasCount = Object.prototype.hasOwnProperty.call(options, 'count');
  return hasCount
    ? !options.case || TURKISH_SUFFIX_CASES.has(options.case)
    : TURKISH_SUFFIX_CASES.has(options.case);
}

function isHindiPronounUsage(locale: string | null | undefined, options: Record<string, string>) {
  return isLocale(locale, 'hi') && hasHindiPronounMarker(options);
}

function hasHindiPronounMarker(options: Record<string, string>) {
  return Object.keys(options).some((option) => HINDI_PRONOUN_MARKER_OPTIONS.has(option));
}

function requiresUnsupportedRuntimeTermInflectionDiagnostic(
  locale: string | null | undefined,
  options: Record<string, string>,
) {
  return (
    Object.keys(options).length > 0 &&
    RUNTIME_TERM_INFLECTION_UNSUPPORTED_LOCALES.has(primaryLocaleSubtag(locale))
  );
}

function primaryLocaleSubtag(locale: string | null | undefined) {
  return locale?.trim().toLowerCase().split(/[-_]/u)[0] ?? '';
}

function runtimeVariableName(value: string) {
  return VARIABLE_REFERENCE_PATTERN.exec(value)?.[0].slice(1) ?? null;
}

function isPortuguesePrepositionArticleCombination(
  preposition: string | undefined,
  article: string | undefined,
) {
  if (!preposition) {
    return article === 'definite' || article === 'indefinite';
  }
  switch (preposition) {
    case 'de':
    case 'por':
      return article === 'definite';
    case 'em':
      return article === 'definite' || article === 'indefinite';
    default:
      return false;
  }
}

function isLocale(locale: string | null | undefined, language: string): boolean {
  return (
    locale === language ||
    Boolean(locale?.startsWith(`${language}-`)) ||
    Boolean(locale?.startsWith(`${language}_`))
  );
}
