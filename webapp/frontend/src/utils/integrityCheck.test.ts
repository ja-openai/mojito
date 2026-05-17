// @vitest-environment node

import { describe, expect, it } from 'vitest';

import { buildIntegrityCheckErrorReport } from './integrityCheck';

describe('buildIntegrityCheckErrorReport', () => {
  it('includes optional context links in the shareable report', () => {
    const report = buildIntegrityCheckErrorReport({
      url: 'https://mojito.example/text-units/42?locale=fr-FR',
      additionalLinks: [
        {
          label: 'Review project text unit URL',
          url: 'https://mojito.example/review-projects/7?tu=42',
        },
        {
          label: 'Ignored empty URL',
          url: ' ',
        },
      ],
      suggestedTranslation: 'Bonjour {name}',
      errorMessage: 'Missing placeholder {count}',
    });

    expect(report.reportMessage).toContain(
      '*URL*\n\nhttps://mojito.example/text-units/42?locale=fr-FR',
    );
    expect(report.reportMessage).toContain(
      '*Review project text unit URL*\n\nhttps://mojito.example/review-projects/7?tu=42',
    );
    expect(report.reportMessage).not.toContain('Ignored empty URL');
    expect(report.reportHtml).toContain('<strong>Review project text unit URL</strong>');
    expect(report.reportHtml).toContain(
      '<a href="https://mojito.example/review-projects/7?tu=42">',
    );
  });
});
