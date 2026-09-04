import type { AiReviewMessage } from '../api/ai-review';

const MAX_REPORTED_POSITIONS = 20;

export function buildAiTranslationContextMessage(
  warnings: ReadonlyArray<{ code: string; message: string }>,
  target: string,
): AiReviewMessage | null {
  const sections: string[] = [];
  const qualityWarnings = warnings.filter(
    (warning) => warning.code !== 'nbsp' && warning.code !== 'nnbsp',
  );
  if (qualityWarnings.length > 0) {
    sections.push(
      [
        'Context only: deterministic translation quality warnings for current target text.',
        'Use these warnings when scoring and proposing edits.',
        ...qualityWarnings.map((warning) => `- ${warning.code}: ${warning.message}`),
      ].join('\n'),
    );
  }

  const spaces = [
    { character: '\u00a0', label: 'NBSP (U+00A0)', count: 0, positions: [] as number[] },
    { character: '\u202f', label: 'NNBSP (U+202F)', count: 0, positions: [] as number[] },
  ];
  let position = 0;
  for (const character of target) {
    position += 1;
    const space = spaces.find((entry) => entry.character === character);
    if (space) {
      space.count += 1;
      if (space.positions.length < MAX_REPORTED_POSITIONS) {
        space.positions.push(position);
      }
    }
  }
  const observations = spaces.filter((space) => space.count > 0);
  if (observations.length > 0) {
    sections.push(
      [
        'Context only: neutral character observations for current target text.',
        'NBSP and narrow NBSP may be intentional or required. Their presence is not by itself a quality defect, even when the source does not contain them.',
        'Assess placement using the target locale, surrounding text, and supplied style guidance. Flag and explain only a specific misuse; preserve valid non-breaking spaces.',
        'Positions are 1-based Unicode code point positions in the raw target text.',
        ...observations.map(
          (space) =>
            `- ${space.label}: ${space.count} ${space.count === 1 ? 'occurrence' : 'occurrences'}; positions ${space.positions.join(', ')}${
              space.count > MAX_REPORTED_POSITIONS ? ` (first ${MAX_REPORTED_POSITIONS} shown)` : ''
            }.`,
        ),
      ].join('\n'),
    );
  }

  return sections.length > 0 ? { role: 'user', content: sections.join('\n\n') } : null;
}
