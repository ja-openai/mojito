import './mf2-document-preview.css';

import { useMemo } from 'react';

import type { VisibleTextMarksMode } from '../visibleTextFormatting';
import { VisibleTextRenderer } from '../VisibleTextRenderer';
import { mf2DocumentPreview } from './preview';

/** Read-only serialization using the same structure/body/atom grammar as the MF1 editor. */
export function Mf2DocumentPreview({
  value,
  lang,
  marksMode,
}: {
  value: string;
  lang?: string;
  marksMode: VisibleTextMarksMode;
}) {
  const preview = useMemo(() => mf2DocumentPreview(value), [value]);
  const ranges: Array<{ start: number; end: number; pattern: boolean }> = [];
  let cursor = 0;
  for (const range of preview.patternRanges) {
    if (cursor < range.start) ranges.push({ start: cursor, end: range.start, pattern: false });
    ranges.push({ ...range, pattern: true });
    cursor = range.end;
  }
  if (cursor < value.length) ranges.push({ start: cursor, end: value.length, pattern: false });
  const structured = preview.patternRanges.some(
    (range) => range.start > 0 || range.end < value.length,
  );

  return (
    <div
      className={`visible-text-renderer mf2-document-preview${structured ? ' mf2-document-preview--structured' : ''}`}
      dir="ltr"
      lang={lang}
    >
      {ranges.map((range) => (
        <VisibleTextRenderer
          as="span"
          className={`mf2-document-preview__${range.pattern ? 'pattern' : 'structure'}`}
          dir={range.pattern ? 'auto' : 'ltr'}
          key={`${range.start}-${range.end}`}
          marksMode={marksMode}
          protectedTokens={preview.protectedTokens
            .filter((token) => token.start >= range.start && token.end <= range.end)
            .map((token) => ({
              ...token,
              start: token.start - range.start,
              end: token.end - range.start,
            }))}
          spellCheck={false}
          value={value.slice(range.start, range.end)}
        />
      ))}
    </div>
  );
}
