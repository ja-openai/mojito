import {
  checkTextUnitIntegrity,
  type TextUnitIntegrityCheckRequest,
  type TextUnitIntegrityCheckResult,
} from '../api/text-units';

export const INTEGRITY_CHECK_UNAVAILABLE_TITLE =
  'The string verification system is temporarily down';
export const INTEGRITY_CHECK_UNAVAILABLE_MESSAGE = 'Try again, or close without saving?';
export const INTEGRITY_CHECK_FAILURE_MESSAGE =
  'This translation failed the placeholder/integrity check. Please fix the translation and try saving again.';

const INTEGRITY_CHECK_RETRY_DELAYS_MS = [500, 1500];

const delay = (milliseconds: number) =>
  new Promise((resolve) => {
    window.setTimeout(resolve, milliseconds);
  });

const isTransientIntegrityCheckError = (error: unknown) => {
  const status = (error as { status?: number } | null)?.status;
  return status == null || status === 408 || status === 429 || status >= 500;
};

export const checkTextUnitIntegrityWithRetry = async (
  request: TextUnitIntegrityCheckRequest,
): Promise<TextUnitIntegrityCheckResult> => {
  for (let attempt = 0; attempt <= INTEGRITY_CHECK_RETRY_DELAYS_MS.length; attempt += 1) {
    try {
      return await checkTextUnitIntegrity(request);
    } catch (error) {
      const canRetry =
        attempt < INTEGRITY_CHECK_RETRY_DELAYS_MS.length && isTransientIntegrityCheckError(error);
      if (!canRetry) {
        throw error;
      }
      await delay(INTEGRITY_CHECK_RETRY_DELAYS_MS[attempt]);
    }
  }
  throw new Error(INTEGRITY_CHECK_UNAVAILABLE_TITLE);
};

export const formatIntegrityCheckFailureBody = (result: TextUnitIntegrityCheckResult | null) => {
  const detail = result?.failureDetail?.trim();
  if (detail) {
    return `${INTEGRITY_CHECK_FAILURE_MESSAGE}\n\n${detail}`;
  }
  return INTEGRITY_CHECK_FAILURE_MESSAGE;
};

const escapeReportHtml = (value: string) =>
  value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
    .replace(/\n/g, '<br>');

type IntegrityCheckReportLink = {
  label: string;
  url?: string | null;
};

export const buildIntegrityCheckErrorReport = ({
  url,
  additionalLinks = [],
  suggestedTranslation,
  errorMessage,
}: {
  url: string;
  additionalLinks?: IntegrityCheckReportLink[];
  suggestedTranslation: string;
  errorMessage: string;
}) => {
  const reportLinks = [{ label: 'URL', url }, ...additionalLinks]
    .map((link) => ({ label: link.label.trim(), url: link.url?.trim() ?? '' }))
    .filter((link) => link.label && link.url);
  const reportMessage = [
    'Hi, I need help with a translation that does not pass the integrity check.',
    ...reportLinks.flatMap((link) => [`*${link.label}*`, link.url]),
    '*Suggested translation*',
    suggestedTranslation,
    '*Error message*:',
    errorMessage,
  ].join('\n\n');
  const reportLinkHtml = reportLinks
    .map(
      (link) =>
        `<p><strong>${escapeReportHtml(link.label)}</strong><br><a href="${escapeReportHtml(
          link.url,
        )}">${escapeReportHtml(link.url)}</a></p>`,
    )
    .join('');
  const reportHtml = [
    '<div>',
    '<p>Hi, I need help with a translation that does not pass the integrity check.</p>',
    reportLinkHtml,
    `<p><strong>Suggested translation</strong><br>${escapeReportHtml(suggestedTranslation)}</p>`,
    `<p><strong>Error message:</strong><br>${escapeReportHtml(errorMessage)}</p>`,
    '</div>',
  ].join('');

  return { reportMessage, reportHtml };
};
