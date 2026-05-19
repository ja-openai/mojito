import type { ApiGlossaryTermEvidence } from '../api/glossaries';

export type GlossaryTermScreenshotEvidence = ApiGlossaryTermEvidence & { imageKey: string };

export function getGlossaryTermScreenshotEvidence(
  evidence: ApiGlossaryTermEvidence[] | null | undefined,
): GlossaryTermScreenshotEvidence[] {
  return (evidence ?? []).flatMap((item) => {
    const imageKey = item.imageKey?.trim();
    if (item.evidenceType !== 'SCREENSHOT' || !imageKey) {
      return [];
    }
    return [{ ...item, imageKey }];
  });
}

export function getGlossaryTermScreenshotKeys(
  evidence: ApiGlossaryTermEvidence[] | null | undefined,
): string[] {
  return getGlossaryTermScreenshotEvidence(evidence).map((item) => item.imageKey);
}
