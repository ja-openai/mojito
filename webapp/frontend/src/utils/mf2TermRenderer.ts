const TERM_EXPRESSION_PATTERN = /\{\$(?<argument>[A-Za-z_][\w.-]*)\s+:term(?<options>[^{}]*)\}/gu;
const PLACEHOLDER_PATTERN = /\{\$(?<name>[A-Za-z_][\w.-]*)\}/gu;
const VARIABLE_REFERENCE_PATTERN = /^\$[A-Za-z_][\w.-]*$/u;

const SUPPORTED_OPTIONS = new Set([
  'article',
  'case',
  'count',
  'definiteness',
  'number',
  'preposition',
]);
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
const SPANISH_LOCALE = 'es';
const ITALIAN_LOCALE = 'it';
const PORTUGUESE_LOCALE = 'pt';
const TURKISH_LOCALE = 'tr';
const GENDER_SHIFT = 4;
const GENDER_MASK = 0xf;
const MASCULINE_GENDER = 1;
const FEMININE_GENDER = 2;
const STRESSED_BIT = 1 << 14;
const ARTICLE_CLASS_SHIFT = 16;
const ARTICLE_CLASS_MASK = 0xf;
const STANDARD_ARTICLE_CLASS = 1;
const LO_ARTICLE_CLASS = 2;
const ELISION_ARTICLE_CLASS = 3;
const TURKISH_SUFFIX_METADATA_BIT = 1 << 20;
const TURKISH_VOWEL_END_BIT = 1 << 21;
const TURKISH_FRONT_VOWEL_BIT = 1 << 22;
const TURKISH_ROUNDED_VOWEL_BIT = 1 << 23;
const TURKISH_HARD_CONSONANT_BIT = 1 << 24;

type CompiledFormRow = {
  key: number;
  kind: string;
  pattern?: boolean;
  value: number;
};

type CompiledFormSet = {
  forms: CompiledFormRow[];
  term: number;
};

type CompiledTermRow = {
  featureBits: number;
  formSet: number;
  id: number;
  sense?: number | null;
  text: number;
};

type CompiledTermPack = {
  formSets: CompiledFormSet[];
  locale: string;
  schema: string;
  strings: string[];
  terms: CompiledTermRow[];
};

type TermUsage = {
  argument: string;
  end: number;
  options: Record<string, string>;
  start: number;
};

type FormSelection = {
  key: string;
  number: string;
};

type Gender = 'feminine' | 'masculine';

type TurkishSuffixMetadata = {
  frontVowel: boolean;
  hardConsonant: boolean;
  roundedVowel: boolean;
  vowelEnd: boolean;
};

export function renderMf2TermMessage(
  pack: unknown,
  message: string,
  termArguments: Record<string, string>,
  variables: Record<string, string>,
): string {
  const renderer = new Mf2CompiledTermRenderer(pack);
  return renderer.renderMessage(message, termArguments, variables);
}

export class Mf2CompiledTermRenderer {
  private readonly formsByTermId = new Map<string, Map<string, CompiledFormRow>>();
  private readonly locale: string;
  private readonly strings: string[];
  private readonly termsByTermId = new Map<string, CompiledTermRow>();

  constructor(pack: unknown) {
    const compiledPack = validateCompiledTermPack(pack);
    this.locale = compiledPack.locale;
    this.strings = compiledPack.strings;

    for (const term of compiledPack.terms) {
      const termId = this.strings[term.id];
      if (!termId) {
        throw new Error(`Missing string for compiled term row index: ${term.id}`);
      }
      if (this.termsByTermId.has(termId)) {
        throw new Error(`Duplicate compiled term row: ${termId}`);
      }
      this.termsByTermId.set(termId, term);
    }

    for (const formSet of compiledPack.formSets) {
      const termId = this.strings[formSet.term];
      if (!termId) {
        throw new Error(`Missing string for compiled term index: ${formSet.term}`);
      }
      const formsByKey = new Map<string, CompiledFormRow>();
      for (const form of formSet.forms) {
        const formKey = this.strings[form.key];
        if (!formKey) {
          throw new Error(`Missing string for compiled form key index: ${form.key}`);
        }
        if (formsByKey.has(formKey)) {
          throw new Error(`Duplicate compiled form key for term ${termId}: ${formKey}`);
        }
        formsByKey.set(formKey, form);
      }
      if (this.formsByTermId.has(termId)) {
        throw new Error(`Duplicate compiled form set for term: ${termId}`);
      }
      this.formsByTermId.set(termId, formsByKey);
    }
  }

  renderMessage(
    message: string,
    termArguments: Record<string, string>,
    variables: Record<string, string>,
  ): string {
    let rendered = '';
    let cursor = 0;
    for (const usage of extractTermUsages(message)) {
      rendered += message.slice(cursor, usage.start);
      rendered += this.renderTerm(
        termArguments[usage.argument],
        usage.options,
        variables,
        usage.argument,
      );
      cursor = usage.end;
    }
    rendered += message.slice(cursor);
    return renderPattern(rendered, variables);
  }

  renderTerm(
    termId: string | undefined,
    options: Record<string, string>,
    variables: Record<string, string>,
    argument?: string,
  ): string {
    if (!termId) {
      throw new Error(argument ? `Missing term argument: ${argument}` : 'Missing term id');
    }
    const forms = this.formsByTermId.get(termId);
    if (!forms) {
      throw new Error(`Missing compiled term: ${termId}`);
    }

    const selection = selectForm(options, variables);
    const form = forms.get(selection.key);
    if (!form) {
      const composed = this.maybeRenderComposedTerm(termId, forms, selection, options, variables);
      if (composed != null) {
        return composed;
      }
      throw new Error(`Missing form ${selection.key} for term ${termId}`);
    }
    return renderForm(form, this.strings, variables);
  }

  private maybeRenderComposedTerm(
    termId: string,
    forms: Map<string, CompiledFormRow>,
    selection: FormSelection,
    options: Record<string, string>,
    variables: Record<string, string>,
  ): string | null {
    return (
      this.maybeRenderComposedSpanishArticleTerm(termId, forms, selection, options, variables) ??
      this.maybeRenderComposedItalianArticleTerm(termId, forms, selection, options, variables) ??
      this.maybeRenderComposedPortugueseArticleTerm(termId, forms, selection, options, variables) ??
      this.maybeRenderComposedTurkishSuffixTerm(termId, forms, selection, options, variables)
    );
  }

  private maybeRenderComposedSpanishArticleTerm(
    termId: string,
    forms: Map<string, CompiledFormRow>,
    selection: FormSelection,
    options: Record<string, string>,
    variables: Record<string, string>,
  ): string | null {
    if (!this.isSpanishArticleComposition(options)) {
      return null;
    }
    const term = this.requiredTerm(termId, 'Spanish');
    const article = spanishArticle(
      options.article,
      gender(termId, 'Spanish', term.featureBits),
      selection.number,
      (term.featureBits & STRESSED_BIT) !== 0,
    );
    return `${article} ${this.renderBareForm(termId, 'Spanish', forms, selection.number, variables)}`;
  }

  private maybeRenderComposedItalianArticleTerm(
    termId: string,
    forms: Map<string, CompiledFormRow>,
    selection: FormSelection,
    options: Record<string, string>,
    variables: Record<string, string>,
  ): string | null {
    if (!this.isItalianArticleComposition(options)) {
      return null;
    }
    const term = this.requiredTerm(termId, 'Italian');
    const article = italianArticle(
      options.article,
      gender(termId, 'Italian', term.featureBits),
      selection.number,
      italianArticleClass(termId, term.featureBits),
    );
    return articlePhrase(
      article,
      this.renderBareForm(termId, 'Italian', forms, selection.number, variables),
    );
  }

  private maybeRenderComposedPortugueseArticleTerm(
    termId: string,
    forms: Map<string, CompiledFormRow>,
    selection: FormSelection,
    options: Record<string, string>,
    variables: Record<string, string>,
  ): string | null {
    if (!this.isPortugueseArticleComposition(options)) {
      return null;
    }
    const term = this.requiredTerm(termId, 'Portuguese');
    const article = portugueseArticle(
      options.article,
      options.preposition,
      gender(termId, 'Portuguese', term.featureBits),
      selection.number,
    );
    return `${article} ${this.renderBareForm(termId, 'Portuguese', forms, selection.number, variables)}`;
  }

  private maybeRenderComposedTurkishSuffixTerm(
    termId: string,
    forms: Map<string, CompiledFormRow>,
    selection: FormSelection,
    options: Record<string, string>,
    variables: Record<string, string>,
  ): string | null {
    if (!this.isTurkishSuffixComposition(options)) {
      return null;
    }
    const term = this.requiredTerm(termId, 'Turkish');
    if ((term.featureBits & TURKISH_SUFFIX_METADATA_BIT) === 0) {
      throw new Error(
        `Turkish suffix composition requires turkishSuffix metadata for term ${termId}`,
      );
    }
    const bareForm = forms.get('bare.singular');
    if (!bareForm) {
      throw new Error(`Missing Turkish bare form bare.singular for term ${termId}`);
    }
    return turkishInflect(
      renderForm(bareForm, this.strings, variables),
      options.case,
      selection.number,
      turkishSuffixMetadata(term.featureBits),
    );
  }

  private renderBareForm(
    termId: string,
    localeName: string,
    forms: Map<string, CompiledFormRow>,
    number: string,
    variables: Record<string, string>,
  ): string {
    const bareKey = `bare.${number}`;
    const bareForm = forms.get(bareKey);
    if (!bareForm) {
      throw new Error(`Missing ${localeName} bare form ${bareKey} for term ${termId}`);
    }
    return renderForm(bareForm, this.strings, variables);
  }

  private requiredTerm(termId: string, localeName: string): CompiledTermRow {
    const term = this.termsByTermId.get(termId);
    if (!term) {
      throw new Error(`Missing ${localeName} term metadata for term ${termId}`);
    }
    return term;
  }

  private isSpanishArticleComposition(options: Record<string, string>) {
    return (
      isLocale(this.locale, SPANISH_LOCALE) &&
      !options.case &&
      !options.preposition &&
      (options.article === 'definite' || options.article === 'indefinite')
    );
  }

  private isItalianArticleComposition(options: Record<string, string>) {
    return (
      isLocale(this.locale, ITALIAN_LOCALE) &&
      !options.case &&
      !options.preposition &&
      (options.article === 'definite' || options.article === 'indefinite')
    );
  }

  private isPortugueseArticleComposition(options: Record<string, string>) {
    return (
      isLocale(this.locale, PORTUGUESE_LOCALE) &&
      !options.case &&
      isPortuguesePrepositionArticleCombination(options.preposition, options.article)
    );
  }

  private isTurkishSuffixComposition(options: Record<string, string>) {
    if (
      !isLocale(this.locale, TURKISH_LOCALE) ||
      options.article ||
      options.preposition ||
      options.definiteness
    ) {
      return false;
    }
    const hasCount = Object.prototype.hasOwnProperty.call(options, 'count');
    return hasCount
      ? !options.case || isSupportedTurkishSuffixCase(options.case)
      : isSupportedTurkishSuffixCase(options.case);
  }
}

function validateCompiledTermPack(pack: unknown): CompiledTermPack {
  if (pack == null || typeof pack !== 'object' || Array.isArray(pack)) {
    throw new Error('Compiled term pack must be a JSON object.');
  }
  const candidate = pack as Partial<CompiledTermPack>;
  if (candidate.schema !== 'mojito-mf2-inflection/compiled-term-pack/v0') {
    throw new Error('Unsupported compiled term pack schema.');
  }
  if (!Array.isArray(candidate.strings)) {
    throw new Error('Compiled term pack strings must be an array.');
  }
  if (!Array.isArray(candidate.formSets)) {
    throw new Error('Compiled term pack formSets must be an array.');
  }
  if (!Array.isArray(candidate.terms)) {
    throw new Error('Compiled term pack terms must be an array.');
  }
  return candidate as CompiledTermPack;
}

function extractTermUsages(message: string): TermUsage[] {
  const usages: TermUsage[] = [];
  for (const match of message.matchAll(TERM_EXPRESSION_PATTERN)) {
    if (match.index == null || !match.groups) {
      continue;
    }
    usages.push({
      argument: match.groups.argument,
      end: match.index + match[0].length,
      options: parseOptions(match.groups.options),
      start: match.index,
    });
  }
  return usages;
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

function selectForm(
  options: Record<string, string>,
  variables: Record<string, string>,
): FormSelection {
  validateOptions(options);
  const countReference = options.count;
  const explicitNumber = options.number;
  const number = explicitNumber ?? numberFromCountReference(countReference, variables);

  if (options.preposition) {
    return {
      key: `preposition.${options.preposition}.${options.article}.${number}`,
      number,
    };
  }
  if (options.definiteness && options.case) {
    return { key: `${options.definiteness}.${options.case}.${number}`, number };
  }
  if (options.definiteness) {
    return { key: `${options.definiteness}.${number}`, number };
  }
  if (options.article && options.case) {
    return { key: `${options.article}.${options.case}.${number}`, number };
  }
  if (options.article) {
    return { key: `${options.article}.${number}`, number };
  }
  if (options.case) {
    return { key: `${options.case}.${number}`, number };
  }
  if (explicitNumber) {
    return { key: `bare.${number}`, number };
  }
  if (countReference) {
    return { key: `count.${number === 'singular' ? 'one' : 'other'}`, number };
  }
  return { key: 'bare.singular', number: 'singular' };
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

function numberFromCountReference(
  countReference: string | undefined,
  variables: Record<string, string>,
): string {
  if (!countReference) {
    return 'singular';
  }
  const variableName = countReference.slice(1);
  const value = variables[variableName];
  if (value == null) {
    throw new Error(`Missing count variable: ${variableName}`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Count variable must be numeric: ${value}`);
  }
  return parsed === 1 ? 'singular' : 'plural';
}

function isLocale(locale: string, language: string): boolean {
  return (
    locale === language || locale.startsWith(`${language}-`) || locale.startsWith(`${language}_`)
  );
}

function gender(termId: string, localeName: string, featureBits: number): Gender {
  const decodedGender = (featureBits >> GENDER_SHIFT) & GENDER_MASK;
  if (decodedGender === MASCULINE_GENDER) {
    return 'masculine';
  }
  if (decodedGender === FEMININE_GENDER) {
    return 'feminine';
  }
  throw new Error(
    `${localeName} article composition requires masculine or feminine gender for term ${termId}`,
  );
}

function spanishArticle(
  article: string | undefined,
  genderValue: Gender,
  number: string,
  stressed: boolean,
): string {
  if (article === 'definite') {
    if (number === 'plural') {
      return genderValue === 'masculine' ? 'los' : 'las';
    }
    return genderValue === 'feminine' && !stressed ? 'la' : 'el';
  }
  if (number === 'plural') {
    return genderValue === 'masculine' ? 'unos' : 'unas';
  }
  return genderValue === 'feminine' && !stressed ? 'una' : 'un';
}

function italianArticleClass(termId: string, featureBits: number): string {
  const articleClass = (featureBits >> ARTICLE_CLASS_SHIFT) & ARTICLE_CLASS_MASK;
  if (articleClass === STANDARD_ARTICLE_CLASS) {
    return 'standard';
  }
  if (articleClass === LO_ARTICLE_CLASS) {
    return 'lo';
  }
  if (articleClass === ELISION_ARTICLE_CLASS) {
    return 'elision';
  }
  throw new Error(`Italian article composition requires articleClass metadata for term ${termId}`);
}

function italianArticle(
  article: string | undefined,
  genderValue: Gender,
  number: string,
  articleClass: string,
): string {
  if (article === 'definite') {
    if (genderValue === 'masculine' && number === 'singular') {
      switch (articleClass) {
        case 'standard':
          return 'il';
        case 'lo':
          return 'lo';
        case 'elision':
          return "l'";
        default:
          throw new Error('Unsupported Italian article class');
      }
    }
    if (genderValue === 'masculine' && number === 'plural') {
      return articleClass === 'standard' ? 'i' : 'gli';
    }
    if (genderValue === 'feminine' && number === 'singular') {
      return articleClass === 'elision' ? "l'" : 'la';
    }
    return 'le';
  }

  if (genderValue === 'masculine' && number === 'singular') {
    return articleClass === 'lo' ? 'uno' : 'un';
  }
  if (genderValue === 'masculine' && number === 'plural') {
    return articleClass === 'standard' ? 'dei' : 'degli';
  }
  if (genderValue === 'feminine' && number === 'singular') {
    return articleClass === 'elision' ? "un'" : 'una';
  }
  return 'delle';
}

function portugueseArticle(
  article: string | undefined,
  preposition: string | undefined,
  genderValue: Gender,
  number: string,
): string {
  const key = `${preposition ?? 'article'}.${article}`;
  switch (key) {
    case 'article.definite':
      return genderNumberForm(genderValue, number, 'o', 'a', 'os', 'as');
    case 'article.indefinite':
      return genderNumberForm(genderValue, number, 'um', 'uma', 'uns', 'umas');
    case 'de.definite':
      return genderNumberForm(genderValue, number, 'do', 'da', 'dos', 'das');
    case 'em.definite':
      return genderNumberForm(genderValue, number, 'no', 'na', 'nos', 'nas');
    case 'em.indefinite':
      return genderNumberForm(genderValue, number, 'num', 'numa', 'nuns', 'numas');
    case 'por.definite':
      return genderNumberForm(genderValue, number, 'pelo', 'pela', 'pelos', 'pelas');
    default:
      throw new Error(`Unsupported Portuguese article composition: ${preposition} + ${article}`);
  }
}

function genderNumberForm(
  genderValue: Gender,
  number: string,
  masculineSingular: string,
  feminineSingular: string,
  masculinePlural: string,
  femininePlural: string,
): string {
  if (number === 'plural') {
    return genderValue === 'masculine' ? masculinePlural : femininePlural;
  }
  return genderValue === 'masculine' ? masculineSingular : feminineSingular;
}

function articlePhrase(article: string, bareValue: string): string {
  return article.endsWith("'") ? `${article}${bareValue}` : `${article} ${bareValue}`;
}

function isPortuguesePrepositionArticleCombination(
  preposition: string | undefined,
  article: string | undefined,
): boolean {
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

function isSupportedTurkishSuffixCase(grammaticalCase: string | undefined): boolean {
  return (
    grammaticalCase === 'ablative' ||
    grammaticalCase === 'accusative' ||
    grammaticalCase === 'dative' ||
    grammaticalCase === 'locative' ||
    grammaticalCase === 'nominative'
  );
}

function turkishSuffixMetadata(featureBits: number): TurkishSuffixMetadata {
  return {
    frontVowel: (featureBits & TURKISH_FRONT_VOWEL_BIT) !== 0,
    hardConsonant: (featureBits & TURKISH_HARD_CONSONANT_BIT) !== 0,
    roundedVowel: (featureBits & TURKISH_ROUNDED_VOWEL_BIT) !== 0,
    vowelEnd: (featureBits & TURKISH_VOWEL_END_BIT) !== 0,
  };
}

function turkishInflect(
  stem: string,
  grammaticalCase: string | undefined,
  number: string,
  metadata: TurkishSuffixMetadata,
): string {
  let inflectedStem = stem;
  let suffixMetadata = metadata;
  if (number === 'plural') {
    inflectedStem += metadata.frontVowel ? 'ler' : 'lar';
    suffixMetadata = {
      frontVowel: metadata.frontVowel,
      hardConsonant: false,
      roundedVowel: false,
      vowelEnd: false,
    };
  }

  if (!grammaticalCase || grammaticalCase === 'nominative') {
    return inflectedStem;
  }
  return `${inflectedStem}${turkishCaseSuffix(grammaticalCase, suffixMetadata)}`;
}

function turkishCaseSuffix(grammaticalCase: string, metadata: TurkishSuffixMetadata): string {
  const twoWayVowel = metadata.frontVowel ? 'e' : 'a';
  const consonant = metadata.hardConsonant ? 't' : 'd';
  switch (grammaticalCase) {
    case 'accusative':
      return `${metadata.vowelEnd ? 'y' : ''}${turkishFourWayVowel(metadata)}`;
    case 'dative':
      return `${metadata.vowelEnd ? 'y' : ''}${twoWayVowel}`;
    case 'locative':
      return `${consonant}${twoWayVowel}`;
    case 'ablative':
      return `${consonant}${twoWayVowel}n`;
    default:
      throw new Error(`Unsupported Turkish case: ${grammaticalCase}`);
  }
}

function turkishFourWayVowel(metadata: TurkishSuffixMetadata): string {
  if (metadata.frontVowel) {
    return metadata.roundedVowel ? 'ü' : 'i';
  }
  return metadata.roundedVowel ? 'u' : 'ı';
}

function renderForm(
  form: CompiledFormRow,
  strings: string[],
  variables: Record<string, string>,
): string {
  const value = strings[form.value];
  if (value == null) {
    throw new Error(`Missing string for compiled form value index: ${form.value}`);
  }
  return form.pattern === true ? renderPattern(value, variables) : value;
}

function renderPattern(value: string, variables: Record<string, string>): string {
  return value.replace(PLACEHOLDER_PATTERN, (_match, name: string) => {
    const replacement = variables[name];
    if (replacement == null) {
      throw new Error(`Missing pattern variable: ${name}`);
    }
    return replacement;
  });
}
