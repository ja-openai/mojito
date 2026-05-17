import {
  type ApiAiTranslateTextUnitAttempt,
  buildAiTranslateAttemptDetailsUrl,
  buildAiTranslateAttemptPayloadUrl,
} from '../api/text-units';
import type { TextUnitHistoryTimelineAiTranslateAttempt } from '../components/TextUnitHistoryTimeline';

export function buildAiTranslateAttemptTimelineData(
  attempts: ApiAiTranslateTextUnitAttempt[],
  tmTextUnitId: number,
  localeTag: string,
): {
  byVariantId: Map<number, TextUnitHistoryTimelineAiTranslateAttempt[]>;
  unlinked: TextUnitHistoryTimelineAiTranslateAttempt[];
} {
  const byVariantId = new Map<number, TextUnitHistoryTimelineAiTranslateAttempt[]>();
  const unlinked: TextUnitHistoryTimelineAiTranslateAttempt[] = [];

  attempts.forEach((attempt) => {
    const timelineAttempt = toTimelineAttempt(attempt, tmTextUnitId, localeTag);
    const variantId = attempt.tmTextUnitVariantId;
    if (typeof variantId !== 'number') {
      unlinked.push(timelineAttempt);
      return;
    }

    byVariantId.set(variantId, [...(byVariantId.get(variantId) ?? []), timelineAttempt]);
  });

  return { byVariantId, unlinked };
}

function toTimelineAttempt(
  attempt: ApiAiTranslateTextUnitAttempt,
  tmTextUnitId: number,
  localeTag: string,
): TextUnitHistoryTimelineAiTranslateAttempt {
  return {
    key: String(attempt.id),
    createdDate: attempt.createdDate ?? null,
    status: attempt.status ?? '-',
    translateType: attempt.translateType ?? null,
    model: attempt.model ?? null,
    completionId: attempt.completionId ?? null,
    requestGroupId: attempt.requestGroupId ?? null,
    detailsUrl: buildAiTranslateAttemptDetailsUrl(tmTextUnitId, attempt.id, localeTag),
    requestPayloadUrl:
      attempt.hasRequestPayload === true
        ? buildAiTranslateAttemptPayloadUrl(tmTextUnitId, attempt.id, localeTag, 'request')
        : null,
    responsePayloadUrl:
      attempt.hasResponsePayload === true
        ? buildAiTranslateAttemptPayloadUrl(tmTextUnitId, attempt.id, localeTag, 'response')
        : null,
    errorMessage: attempt.errorMessage ?? null,
  };
}
