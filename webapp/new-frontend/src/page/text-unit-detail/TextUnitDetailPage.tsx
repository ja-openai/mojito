import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import {
  type ApiGitBlameWithUsage,
  type ApiTextUnitHistoryItem,
  fetchGitBlameWithUsages,
  fetchTextUnitHistory,
  searchTextUnits,
  type TextUnitSearchRequest,
} from '../../api/text-units';
import {
  type TextUnitDetailHistoryComment,
  type TextUnitDetailHistoryRow,
  type TextUnitDetailMetaRow,
  type TextUnitDetailMetaSection,
  TextUnitDetailPageView,
} from './TextUnitDetailPageView';

type LocationState = {
  from?: string;
  workbenchSearch?: TextUnitSearchRequest | null;
};

export function TextUnitDetailPage() {
  const { tmTextUnitId: tmTextUnitIdParam } = useParams<{ tmTextUnitId: string }>();
  const parsedTmTextUnitId = tmTextUnitIdParam ? Number(tmTextUnitIdParam) : NaN;
  const tmTextUnitId =
    Number.isFinite(parsedTmTextUnitId) && parsedTmTextUnitId > 0 ? parsedTmTextUnitId : null;

  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const locationState = (location.state as LocationState | null) ?? null;

  const localeTag = useMemo(() => searchParams.get('locale')?.trim() ?? null, [searchParams]);

  const textUnitQuery = useQuery({
    queryKey: ['text-unit-detail', tmTextUnitId, localeTag],
    enabled: tmTextUnitId !== null && Boolean(localeTag),
    staleTime: 30_000,
    queryFn: async () => {
      if (tmTextUnitId === null || !localeTag) {
        return null;
      }

      const results = await searchTextUnits({
        repositoryIds: [],
        localeTags: [localeTag],
        searchAttribute: 'tmTextUnitIds',
        searchType: 'exact',
        searchText: String(tmTextUnitId),
        limit: 5,
        offset: 0,
      });

      return results.find((item) => item.targetLocale === localeTag) ?? results[0] ?? null;
    },
  });

  const historyQuery = useQuery({
    queryKey: ['text-unit-history', tmTextUnitId, localeTag],
    enabled: tmTextUnitId !== null && Boolean(localeTag),
    staleTime: 30_000,
    queryFn: () => {
      if (tmTextUnitId === null || !localeTag) {
        return Promise.resolve([] as ApiTextUnitHistoryItem[]);
      }
      return fetchTextUnitHistory(tmTextUnitId, localeTag);
    },
  });

  const gitBlameQuery = useQuery({
    queryKey: ['text-unit-git-blame', tmTextUnitId],
    enabled: tmTextUnitId !== null,
    staleTime: 30_000,
    queryFn: () => {
      if (tmTextUnitId === null) {
        return Promise.resolve([] as ApiGitBlameWithUsage[]);
      }
      return fetchGitBlameWithUsages(tmTextUnitId);
    },
  });

  const gitBlame = useMemo(() => gitBlameQuery.data?.[0] ?? null, [gitBlameQuery.data]);

  const sortedHistoryItems = useMemo(() => {
    return [...(historyQuery.data ?? [])].sort((a, b) => {
      const dateDelta = safeDateValue(b.createdDate) - safeDateValue(a.createdDate);
      if (dateDelta !== 0) {
        return dateDelta;
      }
      return (b.id ?? 0) - (a.id ?? 0);
    });
  }, [historyQuery.data]);

  const textUnitLocation = useMemo(() => {
    const usagesFromGitBlame = gitBlame?.usages ?? [];
    if (usagesFromGitBlame.length > 0) {
      return usagesFromGitBlame.join(', ');
    }

    const usages =
      textUnitQuery.data?.assetTextUnitUsages
        ?.split(',')
        .map((value) => value.trim())
        .filter(Boolean) ?? [];
    return usages.length > 0 ? usages.join(', ') : null;
  }, [gitBlame?.usages, textUnitQuery.data?.assetTextUnitUsages]);

  const metaSections = useMemo<TextUnitDetailMetaSection[]>(() => {
    const textUnitRows: TextUnitDetailMetaRow[] = [
      { label: 'Repository', value: formatValue(textUnitQuery.data?.repositoryName) },
      { label: 'AssetPath', value: formatValue(textUnitQuery.data?.assetPath) },
      { label: 'Id', value: formatValue(textUnitQuery.data?.name) },
      { label: 'Source', value: formatValue(textUnitQuery.data?.source) },
      { label: 'Target', value: formatValue(textUnitQuery.data?.target) },
      { label: 'Locale', value: formatValue(localeTag ?? textUnitQuery.data?.targetLocale) },
      { label: 'Created', value: formatDateTime(textUnitQuery.data?.tmTextUnitCreatedDate) },
      { label: 'Translated', value: formatDateTime(textUnitQuery.data?.createdDate) },
      { label: 'PluralForm', value: formatValue(textUnitQuery.data?.pluralForm) },
      { label: 'PluralFormOther', value: formatValue(textUnitQuery.data?.pluralFormOther) },
      { label: 'Comment', value: formatValue(textUnitQuery.data?.comment) },
      { label: 'TargetComment', value: formatValue(textUnitQuery.data?.targetComment) },
      { label: 'Location', value: formatValue(textUnitLocation) },
      {
        label: 'Third party TMS',
        value: formatValue(gitBlame?.thirdPartyTextUnitId ? 'See in Phrase' : null),
      },
    ];

    const gitBlameRows: TextUnitDetailMetaRow[] = [
      { label: 'Author', value: formatValue(gitBlame?.gitBlame?.authorName) },
      { label: 'Email', value: formatValue(gitBlame?.gitBlame?.authorEmail) },
      { label: 'Commit', value: formatValue(gitBlame?.gitBlame?.commitName) },
      { label: 'Commit date', value: formatGitCommitTime(gitBlame?.gitBlame?.commitTime) },
    ];

    const moreRows: TextUnitDetailMetaRow[] = [
      { label: 'Virtual', value: formatBoolean(gitBlame?.isVirtual) },
      { label: 'TmTextUnitId', value: formatNumberish(textUnitQuery.data?.tmTextUnitId) },
      { label: 'TmTextUnitVariantId', value: formatNumberish(textUnitQuery.data?.tmTextUnitVariantId) },
      {
        label: 'TmTextUnitCurrentVariantId',
        value: formatNumberish(textUnitQuery.data?.tmTextUnitCurrentVariantId),
      },
      { label: 'AssetTextUnitId', value: formatNumberish(textUnitQuery.data?.assetTextUnitId) },
      { label: 'ThirdPartyTMSId', value: formatValue(gitBlame?.thirdPartyTextUnitId) },
      { label: 'AssetId', value: formatNumberish(textUnitQuery.data?.assetId) },
      {
        label: 'LastSuccessfulAssetExtractionId',
        value: formatNumberish(textUnitQuery.data?.lastSuccessfulAssetExtractionId),
      },
      { label: 'AssetExtractionId', value: formatNumberish(textUnitQuery.data?.assetExtractionId) },
      { label: 'Branch', value: formatValue(gitBlame?.branch?.name) },
      { label: 'Screenshots', value: formatScreenshots(gitBlame?.screenshots) },
    ];

    return [
      { title: 'Text unit', rows: textUnitRows },
      { title: 'Git blame', rows: gitBlameRows },
      { title: 'More', rows: moreRows },
    ];
  }, [gitBlame, localeTag, textUnitLocation, textUnitQuery.data]);

  const historyRows = useMemo<TextUnitDetailHistoryRow[]>(() => {
    return sortedHistoryItems.map((item) => {
      const comments: TextUnitDetailHistoryComment[] = (item.tmTextUnitVariantComments ?? []).map(
        (comment, index) => ({
          key:
            comment.id != null
              ? String(comment.id)
              : `${comment.type ?? ''}-${comment.severity ?? ''}-${comment.content ?? ''}-${index}`,
          type: formatValue(comment.type),
          severity: formatValue(comment.severity),
          content: formatValue(comment.content),
        }),
      );

      return {
        key: String(item.id),
        variantId: String(item.id),
        userName: formatValue(item.createdByUser?.username ?? '<no user>'),
        translation: formatHistoryTranslation(item.content),
        date: formatDateTime(item.createdDate),
        status: formatValue(formatHistoryStatus(item.status, item.includedInLocalizedFile)),
        comments,
      };
    });
  }, [sortedHistoryItems]);

  const handleBack = () => {
    if (locationState?.workbenchSearch) {
      void navigate('/workbench', {
        state: { workbenchSearch: locationState.workbenchSearch },
      });
      return;
    }

    if (window.history.length > 1) {
      void navigate(-1);
      return;
    }
    void navigate(locationState?.from ?? '/workbench');
  };

  if (tmTextUnitId === null) {
    return (
      <div className="review-project-page__state review-project-page__state--error">
        <div>Missing or invalid text unit id.</div>
      </div>
    );
  }

  return (
    <TextUnitDetailPageView
      tmTextUnitId={tmTextUnitId}
      onBack={handleBack}
      isMetaLoading={textUnitQuery.isLoading}
      metaErrorMessage={getQueryErrorMessage(textUnitQuery.error)}
      metaSections={metaSections}
      metaWarningMessage={
        gitBlameQuery.isError
          ? `Git blame metadata is unavailable: ${getQueryErrorMessage(gitBlameQuery.error) ?? '-'}`
          : null
      }
      isHistoryLoading={!localeTag ? false : textUnitQuery.isLoading || historyQuery.isLoading}
      historyErrorMessage={
        !localeTag
          ? null
          : getQueryErrorMessage(textUnitQuery.error) ?? getQueryErrorMessage(historyQuery.error)
      }
      historyMissingLocale={!localeTag}
      historyRows={historyRows}
      historyInitialDate={formatDateTime(textUnitQuery.data?.tmTextUnitCreatedDate)}
    />
  );
}

const safeDateValue = (value?: string | null) => {
  if (!value) {
    return Number.MIN_SAFE_INTEGER;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? Number.MIN_SAFE_INTEGER : parsed;
};

const formatDateTime = (value?: string | null) => {
  if (!value) {
    return '-';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
};

const formatHistoryStatus = (status?: string | null, includedInLocalizedFile?: boolean) => {
  if (includedInLocalizedFile === false) {
    return 'Rejected';
  }

  switch (status) {
    case 'APPROVED':
      return 'Accepted';
    case 'REVIEW_NEEDED':
      return 'To review';
    case 'TRANSLATION_NEEDED':
      return 'To translate';
    default:
      return null;
  }
};

const getQueryErrorMessage = (error: unknown) => {
  if (error instanceof Error) {
    return error.message;
  }
  return null;
};

const formatGitCommitTime = (commitTime?: string | null) => {
  if (!commitTime) {
    return '-';
  }

  const parsedSeconds = Number.parseInt(commitTime, 10);
  if (!Number.isFinite(parsedSeconds)) {
    return '-';
  }
  return formatDateTime(new Date(parsedSeconds * 1000).toISOString());
};

const formatBoolean = (value?: boolean) => {
  if (typeof value !== 'boolean') {
    return '-';
  }
  return value ? 'true' : 'false';
};

const formatNumberish = (value?: number | null) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return String(value);
};

const formatScreenshots = (
  screenshots?: Array<{
    id?: number | null;
    name?: string | null;
    src?: string | null;
  }> | null,
) => {
  if (!screenshots || screenshots.length === 0) {
    return '-';
  }
  return String(screenshots.length);
};

const formatHistoryTranslation = (translation?: string | null) => {
  if (translation == null) {
    return '<no translation yet>';
  }
  return translation;
};

const formatValue = (value?: string | null) => {
  if (value == null || value.trim().length === 0) {
    return '-';
  }
  return value;
};
