export type ApiLocale = {
  bcp47Tag: string;
};

export type ApiRepositoryLocale = {
  locale: ApiLocale;
  toBeFullyTranslated: boolean;
  parentLocale: ApiLocale | null;
};

export type ApiRepositoryLocaleStatistic = {
  locale: ApiLocale;
  translatedCount?: number | null;
  translatedWordCount?: number | null;
  translationNeededCount?: number | null;
  translationNeededWordCount?: number | null;
  includeInFileCount?: number | null;
  includeInFileWordCount?: number | null;
  reviewNeededCount?: number | null;
  reviewNeededWordCount?: number | null;
  forTranslationCount?: number | null;
  forTranslationWordCount?: number | null;
};

export type ApiRepositoryStatistic = {
  repositoryLocaleStatistics?: ApiRepositoryLocaleStatistic[] | null;
};

export type ApiRepository = {
  id: number;
  name: string;
  sourceLocale?: ApiLocale | null;
  repositoryLocales?: ApiRepositoryLocale[] | null;
  repositoryStatistic?: ApiRepositoryStatistic | null;
  isGlossary?: boolean | null;
};

export const fetchRepositories = async (): Promise<ApiRepository[]> => {
  const response = await fetch('/api/repositories');

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load repositories');
  }

  return (await response.json()) as ApiRepository[];
};
