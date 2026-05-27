import './bidi-helper-page.css';

import { useLayoutEffect, useMemo, useRef, useState } from 'react';

import { useUser } from '../../components/RequireUser';

type Direction = 'auto' | 'ltr' | 'rtl';

type BidiMarker = {
  label: string;
  value: string;
  name: string;
  kind: 'primary' | 'legacy';
};

type BidiItem = {
  char: string;
  code: string;
  direction: string;
  index: number;
  label: string;
  marker: BidiMarker | null;
  start: number;
  end: number;
  visualTop?: number;
  visualLeft?: number;
  visualRight?: number;
};

const PRIMARY_MARKERS: BidiMarker[] = [
  { label: 'LRM', value: '\u200e', name: 'left-to-right mark', kind: 'primary' },
  { label: 'RLM', value: '\u200f', name: 'right-to-left mark', kind: 'primary' },
  { label: 'ALM', value: '\u061c', name: 'Arabic letter mark', kind: 'primary' },
  { label: 'LRI', value: '\u2066', name: 'left-to-right isolate', kind: 'primary' },
  { label: 'RLI', value: '\u2067', name: 'right-to-left isolate', kind: 'primary' },
  { label: 'FSI', value: '\u2068', name: 'first-strong isolate', kind: 'primary' },
  { label: 'PDI', value: '\u2069', name: 'pop directional isolate', kind: 'primary' },
];

const LEGACY_MARKERS: BidiMarker[] = [
  { label: 'LRE', value: '\u202a', name: 'left-to-right embedding', kind: 'legacy' },
  { label: 'RLE', value: '\u202b', name: 'right-to-left embedding', kind: 'legacy' },
  { label: 'PDF', value: '\u202c', name: 'pop directional formatting', kind: 'legacy' },
  { label: 'LRO', value: '\u202d', name: 'left-to-right override', kind: 'legacy' },
  { label: 'RLO', value: '\u202e', name: 'right-to-left override', kind: 'legacy' },
];

const MARKERS = [...PRIMARY_MARKERS, ...LEGACY_MARKERS];

const ISOLATE_WRAPS = {
  ltr: { open: '\u2066', close: '\u2069', label: 'LRI...PDI' },
  rtl: { open: '\u2067', close: '\u2069', label: 'RLI...PDI' },
  auto: { open: '\u2068', close: '\u2069', label: 'FSI...PDI' },
};

const SAMPLES = [
  { label: 'a', value: 'a' },
  { label: 'ש', value: 'ש' },
  { label: 'م', value: 'م' },
  { label: '1', value: '1' },
  { label: '.', value: '.' },
  { label: 'abc', value: 'abc' },
  { label: 'A1', value: 'A1' },
  { label: 'file.txt', value: 'file.txt' },
  { label: 'file-שלום.txt', value: 'file-שלום.txt' },
  {
    label: 'FSI file-שלום.txt',
    value: '\u2068file-שלום.txt\u2069',
    note: 'Filename wrapped in FSI/PDI: file-שלום.txt.',
  },
  {
    label: 'RTL-start filename',
    value: 'שלום-file.txt',
    note: 'Mixed filename starts with Hebrew: שלום-file.txt.',
  },
  {
    label: 'FSI RTL-start filename',
    value: '\u2068שלום-file.txt\u2069',
    note: 'RTL-start mixed filename wrapped in FSI/PDI: שלום-file.txt.',
  },
  {
    label: 'en raw: Hebrew token',
    value: 'what does this mean טעא?',
  },
  {
    label: 'en isolated Hebrew token',
    value: 'what does this mean \u2067טעא\u2069?',
  },
  {
    label: 'en: brands',
    value: 'Acme built Nova.',
    note: 'English: Acme built Nova. Logical order: Acme first, Nova second.',
  },
  {
    label: 'ar raw: brands',
    value: 'تم إنشاء Nova بواسطة Acme.',
    note: 'Arabic meaning: Acme built Nova.',
  },
  {
    label: 'ar isolated: brands',
    value: 'تم إنشاء \u2066Nova\u2069 بواسطة \u2066Acme\u2069.',
    note: 'Arabic with LRI/PDI around the LTR brands.',
  },
  {
    label: 'he raw: brands',
    value: 'Nova נוצר על ידי Acme.',
    note: 'Hebrew: Acme built Nova.',
  },
  {
    label: 'he isolated: brands',
    value: '\u2066Nova\u2069 נוצר על ידי \u2066Acme\u2069.',
    note: 'Hebrew with LRI/PDI around the LTR brands.',
  },
  { label: 'שלום', value: 'שלום' },
  { label: 'مرحبا', value: 'مرحبا' },
  { label: '/', value: ' / ' },
  {
    label: 'LTR sentence',
    value: 'File שלום.txt saved.',
    note: 'Logical sentence: File [Hebrew name].txt saved.',
  },
  {
    label: 'RTL sentence',
    value: 'שלום {userName}, your review is due today.',
    note: 'Mixed Hebrew greeting, placeholder, and English UI text.',
  },
  {
    label: 'Arabic order',
    value: 'Order #12345 - مرحبا {customerName}',
    note: 'Neutral punctuation and numbers next to Arabic text.',
  },
];

const INSERT_TOOLS = [
  { label: 'NBSP', text: '\u00a0', title: 'Insert a no-break space.' },
  { label: 'NNBSP', text: '\u202f', title: 'Insert a narrow no-break space.' },
  {
    label: 'Curly apostrophe',
    text: '\u2019',
    title: 'Insert U+2019 when the OS or IME path is awkward.',
  },
];

const DEFAULT_NOTE =
  'Select a sample or insert Unicode controls to inspect logical and visual order.';

function formatCodePoint(codePoint: number) {
  const hex = codePoint.toString(16).toUpperCase();
  return `U+${hex.padStart(Math.max(4, hex.length), '0')}`;
}

function visibleCharLabel(char: string) {
  if (char === ' ') return 'space';
  if (char === '\n') return 'newline';
  if (char === '\t') return 'tab';
  return char;
}

function markerFor(char: string) {
  return MARKERS.find((item) => item.value === char) ?? null;
}

function isRtlCodePoint(codePoint: number) {
  return (
    (codePoint >= 0x0590 && codePoint <= 0x08ff) ||
    (codePoint >= 0xfb1d && codePoint <= 0xfdff) ||
    (codePoint >= 0xfe70 && codePoint <= 0xfeff)
  );
}

function isLtrCodePoint(codePoint: number) {
  return (
    (codePoint >= 0x0041 && codePoint <= 0x005a) ||
    (codePoint >= 0x0061 && codePoint <= 0x007a) ||
    (codePoint >= 0x00c0 && codePoint <= 0x02af)
  );
}

function directionFor(char: string, marker: BidiMarker | null) {
  if (marker?.label === 'LRM' || marker?.label === 'LRI') return 'LTR control';
  if (marker?.label === 'RLM' || marker?.label === 'ALM' || marker?.label === 'RLI') {
    return 'RTL control';
  }
  if (marker?.label === 'FSI') return 'Auto isolate';
  if (marker?.label === 'PDI') return 'Close isolate';
  if (marker) return 'Legacy control';

  const codePoint = char.codePointAt(0) ?? 0;
  if (isRtlCodePoint(codePoint)) return 'RTL strong';
  if (isLtrCodePoint(codePoint)) return 'LTR strong';
  if (/^\p{Number}$/u.test(char)) return 'Number';
  return 'Neutral';
}

function bidiItems(value: string): BidiItem[] {
  const items: BidiItem[] = [];
  let offset = 0;
  for (const [index, char] of Array.from(value).entries()) {
    const codePoint = char.codePointAt(0) ?? 0;
    const marker = markerFor(char);
    items.push({
      char,
      code: formatCodePoint(codePoint),
      direction: directionFor(char, marker),
      index,
      label: marker?.label ?? visibleCharLabel(char),
      marker,
      start: offset,
      end: offset + char.length,
    });
    offset += char.length;
  }
  return items;
}

function escapedBidiText(value: string) {
  return (
    Array.from(value)
      .map((char) => {
        const marker = markerFor(char);
        if (marker) return `<${marker.label}>`;
        if (char === '\n') return '\\n';
        if (char === '\t') return '\\t';
        return char;
      })
      .join('') || '(empty)'
  );
}

function markerDiagnostics(items: BidiItem[]) {
  const stack: BidiItem[] = [];
  const diagnostics: Array<{ title: string; message: string; severity: 'ok' | 'error' }> = [];
  for (const item of items) {
    const label = item.marker?.label;
    if (label === 'LRI' || label === 'RLI' || label === 'FSI') {
      stack.push(item);
    } else if (label === 'PDI') {
      if (stack.length > 0) {
        stack.pop();
      } else {
        diagnostics.push({
          title: 'Stray PDI',
          message: `PDI at logical cell ${item.index + 1} has no open isolate to close.`,
          severity: 'error',
        });
      }
    }
  }

  for (const item of stack.reverse()) {
    diagnostics.push({
      title: `Unclosed ${item.marker?.label ?? 'isolate'}`,
      message: `${item.marker?.label ?? 'Isolate'} at logical cell ${item.index + 1} needs a later PDI.`,
      severity: 'error',
    });
  }

  if (diagnostics.length === 0) {
    diagnostics.push({
      title: 'Modern isolates balanced',
      message: 'LRM/RLM/ALM are point marks; they do not need closing.',
      severity: 'ok',
    });
  }

  return diagnostics;
}

function visualOrder(items: BidiItem[], preview: HTMLDivElement | null): BidiItem[] {
  const textNode = preview?.firstChild;
  if (!textNode || textNode.nodeType !== Node.TEXT_NODE || items.length === 0) return items;

  const direction = getComputedStyle(preview).direction;
  const range = document.createRange();
  const measured = items.map((item) => {
    try {
      range.setStart(textNode, item.start);
      range.setEnd(textNode, item.end);
      const rect = range.getClientRects()[0] ?? range.getBoundingClientRect();
      return {
        ...item,
        visualLeft: Number.isFinite(rect.left) ? rect.left : item.index,
        visualRight: Number.isFinite(rect.right) ? rect.right : item.index,
        visualTop: Number.isFinite(rect.top) ? rect.top : 0,
      };
    } catch {
      return item;
    }
  });
  range.detach();

  return measured.sort((left, right) => {
    const topDelta = Math.round(left.visualTop ?? 0) - Math.round(right.visualTop ?? 0);
    if (Math.abs(topDelta) > 2) return topDelta;
    if (direction === 'rtl') {
      return (
        (right.visualRight ?? right.index) - (left.visualRight ?? left.index) ||
        left.index - right.index
      );
    }
    return (
      (left.visualLeft ?? left.index) - (right.visualLeft ?? right.index) ||
      left.index - right.index
    );
  });
}

function groupVisualLines(items: BidiItem[]) {
  const lines: BidiItem[][] = [];
  for (const item of items) {
    const last = lines.length > 0 ? lines[lines.length - 1] : undefined;
    const top = Math.round(item.visualTop ?? 0);
    const lastTop = Math.round(last?.[0]?.visualTop ?? top);
    if (!last || Math.abs(top - lastTop) > 2) {
      lines.push([item]);
    } else {
      last.push(item);
    }
  }
  return lines.length > 0 ? lines : [[]];
}

function directionLabel(configured: Direction, resolved: string) {
  const upperResolved = resolved.toUpperCase();
  if (configured === resolved) return upperResolved;
  return `${configured.toUpperCase()} -> ${upperResolved}`;
}

function bdiSnippet(value: string, dir: Direction) {
  return `<bdi dir="${dir}">${value || 'text'}</bdi>`;
}

function plainIsolateSnippet(value: string, direction: Direction) {
  const wrap = ISOLATE_WRAPS[direction];
  return escapedBidiText(`${wrap.open}${value || 'text'}${wrap.close}`);
}

function DirectionButtons({
  ariaLabel,
  value,
  onChange,
}: {
  ariaLabel: string;
  value: Direction;
  onChange: (direction: Direction) => void;
}) {
  return (
    <div className="bidi-helper-page__segmented" role="group" aria-label={ariaLabel}>
      {(['ltr', 'rtl', 'auto'] as const).map((direction) => (
        <button
          key={direction}
          type="button"
          className="btn btn--ghost"
          aria-pressed={value === direction}
          onClick={() => onChange(direction)}
        >
          {direction.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

function OrderStrip({
  items,
  label,
  order,
}: {
  items: BidiItem[];
  label: string;
  order: 'logical' | 'visual';
}) {
  const lines = order === 'visual' ? groupVisualLines(items) : [items];

  return (
    <div className="bidi-helper-page__order-group" aria-label={label}>
      {lines.map((line, lineIndex) => (
        <div key={`${order}-${lineIndex}`} className="bidi-helper-page__order-line">
          {order === 'visual' && (
            <span className="bidi-helper-page__line-label">Line {lineIndex + 1}</span>
          )}
          <div className="bidi-helper-page__order-strip">
            {line.length === 0 ? (
              <span className="hint">No characters yet.</span>
            ) : (
              line.map((item, index) => (
                <span
                  key={`${order}-${item.index}-${item.code}`}
                  className={
                    item.marker
                      ? 'bidi-helper-page__order-cell bidi-helper-page__order-cell--control'
                      : 'bidi-helper-page__order-cell'
                  }
                >
                  <em>{index + 1}</em>
                  <strong dir="auto">{item.label}</strong>
                  <code>{item.code}</code>
                  <span className="bidi-helper-page__order-direction">{item.direction}</span>
                </span>
              ))
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

export function BidiHelperPage() {
  const user = useUser();
  const [text, setText] = useState(SAMPLES[0].value);
  const [note, setNote] = useState(DEFAULT_NOTE);
  const [inputDirection, setInputDirection] = useState<Direction>('ltr');
  const [outputDirection, setOutputDirection] = useState<Direction>('ltr');
  const [resolvedDirections, setResolvedDirections] = useState({ input: 'ltr', output: 'ltr' });
  const [visualItems, setVisualItems] = useState<BidiItem[]>([]);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const outputRef = useRef<HTMLDivElement | null>(null);
  const selectionRef = useRef({ start: SAMPLES[0].value.length, end: SAMPLES[0].value.length });

  const isAdmin = user.role === 'ROLE_ADMIN';
  const items = useMemo(() => bidiItems(text), [text]);
  const diagnostics = useMemo(() => markerDiagnostics(items), [items]);
  const controls = items.filter((item) => item.marker != null);

  useLayoutEffect(() => {
    if (!isAdmin) return;
    setResolvedDirections({
      input: inputRef.current ? getComputedStyle(inputRef.current).direction : inputDirection,
      output: outputRef.current ? getComputedStyle(outputRef.current).direction : outputDirection,
    });
    setVisualItems(visualOrder(items, outputRef.current));
  }, [inputDirection, isAdmin, items, outputDirection, text]);

  function replaceText(
    nextText: string,
    nextNote = note,
    selection?: { start: number; end: number },
  ) {
    const nextSelection = selection ?? { start: nextText.length, end: nextText.length };
    selectionRef.current = nextSelection;
    setText(nextText);
    setNote(nextNote);
    window.requestAnimationFrame(() => {
      inputRef.current?.focus();
      inputRef.current?.setSelectionRange(nextSelection.start, nextSelection.end);
    });
  }

  function rememberSelection(textarea: HTMLTextAreaElement) {
    selectionRef.current = {
      start: textarea.selectionStart ?? text.length,
      end: textarea.selectionEnd ?? textarea.selectionStart ?? text.length,
    };
  }

  function insertIntoTextarea(value: string, nextNote: string) {
    const start = Math.min(selectionRef.current.start, text.length);
    const end = Math.min(selectionRef.current.end, text.length);
    const nextText = `${text.slice(0, start)}${value}${text.slice(end)}`;
    const cursor = start + value.length;
    replaceText(nextText, nextNote, { start: cursor, end: cursor });
  }

  function wrapSelection(direction: Direction) {
    const textarea = inputRef.current;
    const wrap = ISOLATE_WRAPS[direction];
    const selectionStart = textarea?.selectionStart ?? 0;
    const selectionEnd = textarea?.selectionEnd ?? text.length;
    const hasSelection = selectionStart !== selectionEnd;
    const start = hasSelection ? selectionStart : 0;
    const end = hasSelection ? selectionEnd : text.length;
    const selected = text.slice(start, end);
    const nextText = `${text.slice(0, start)}${wrap.open}${selected}${wrap.close}${text.slice(end)}`;
    const innerStart = start + wrap.open.length;
    replaceText(
      nextText,
      `Wrapped ${hasSelection ? 'selected text' : 'the whole textarea'} with ${wrap.label}.`,
      {
        start: innerStart,
        end: innerStart + selected.length,
      },
    );
  }

  if (!isAdmin) {
    return (
      <div className="page-wrapper bidi-helper-page">
        <div className="card card--padded">
          <div className="card__header">
            <div>
              <h1 className="page-title">Bidi Helper</h1>
              <p className="hint">Admin access is required.</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="page-wrapper bidi-helper-page">
      <div className="card card--padded">
        <div className="card__header">
          <div>
            <h1 className="page-title">Bidi Helper</h1>
            <p className="hint">
              Inspect mixed LTR/RTL text, invisible Unicode controls, and copy-safe isolation.
            </p>
          </div>
        </div>
        <div className="card__content card__content--stack">
          <section className="bidi-helper-page__grid" aria-label="Bidi text editor and preview">
            <div className="bidi-helper-page__panel">
              <div className="bidi-helper-page__panel-header">
                <h2>Input</h2>
                <DirectionButtons
                  ariaLabel="Textarea direction"
                  value={inputDirection}
                  onChange={setInputDirection}
                />
              </div>
              <textarea
                ref={inputRef}
                id="bidi-helper-text"
                className="input bidi-helper-page__textarea"
                value={text}
                dir={inputDirection}
                onChange={(event) => {
                  setText(event.target.value);
                  rememberSelection(event.target);
                }}
                onClick={(event) => rememberSelection(event.currentTarget)}
                onFocus={(event) => rememberSelection(event.currentTarget)}
                onKeyUp={(event) => rememberSelection(event.currentTarget)}
                onSelect={(event) => rememberSelection(event.currentTarget)}
                spellCheck={false}
              />
              <div className="bidi-helper-page__samples" aria-label="Sample bidi strings">
                {SAMPLES.map((sample) => (
                  <button
                    key={sample.label}
                    type="button"
                    className="btn btn--ghost"
                    onClick={() => insertIntoTextarea(sample.value, sample.note ?? DEFAULT_NOTE)}
                  >
                    {sample.label}
                  </button>
                ))}
                <button
                  type="button"
                  className="btn btn--ghost"
                  onClick={() => replaceText('', DEFAULT_NOTE)}
                >
                  Clear
                </button>
              </div>
              <p className="bidi-helper-page__note">{note}</p>

              <div className="bidi-helper-page__tool-section">
                <h3>Simple IME</h3>
                <div className="bidi-helper-page__samples" aria-label="Simple text insertions">
                  {INSERT_TOOLS.map((tool) => (
                    <button
                      key={tool.label}
                      type="button"
                      className="btn btn--ghost"
                      title={tool.title}
                      onClick={() => insertIntoTextarea(tool.text, tool.title)}
                    >
                      {tool.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="bidi-helper-page__tool-section">
                <h3>Unicode controls</h3>
                <div className="bidi-helper-page__samples" aria-label="Bidi control insertions">
                  {PRIMARY_MARKERS.map((marker) => (
                    <button
                      key={marker.label}
                      type="button"
                      className="btn btn--ghost"
                      title={marker.name}
                      onClick={() =>
                        insertIntoTextarea(
                          marker.value,
                          `Inserted ${marker.label}: ${marker.name}.`,
                        )
                      }
                    >
                      {marker.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="bidi-helper-page__tool-section">
                <h3>Wrap selection</h3>
                <div
                  className="bidi-helper-page__samples"
                  aria-label="Wrap selected text with an isolate"
                >
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={() => wrapSelection('ltr')}
                  >
                    Wrap LRI...PDI
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={() => wrapSelection('rtl')}
                  >
                    Wrap RLI...PDI
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={() => wrapSelection('auto')}
                  >
                    Wrap FSI...PDI
                  </button>
                </div>
              </div>

              <details className="bidi-helper-page__details">
                <summary>Legacy/debug controls</summary>
                <div className="bidi-helper-page__samples">
                  {LEGACY_MARKERS.map((marker) => (
                    <button
                      key={marker.label}
                      type="button"
                      className="btn btn--ghost"
                      title={marker.name}
                      onClick={() =>
                        insertIntoTextarea(
                          marker.value,
                          `Inserted ${marker.label}: ${marker.name}. Prefer isolates for product text.`,
                        )
                      }
                    >
                      {marker.label}
                    </button>
                  ))}
                </div>
                <p className="hint">
                  Legacy embeddings and overrides close with PDF. Prefer isolates.
                </p>
              </details>
            </div>

            <div className="bidi-helper-page__panel">
              <div className="bidi-helper-page__panel-header">
                <h2>Output</h2>
                <DirectionButtons
                  ariaLabel="Rendered preview direction"
                  value={outputDirection}
                  onChange={setOutputDirection}
                />
              </div>
              <div ref={outputRef} className="bidi-helper-page__preview" dir={outputDirection}>
                {text || ' '}
              </div>
              <div className="bidi-helper-page__summary">
                <span>
                  Input <strong>{directionLabel(inputDirection, resolvedDirections.input)}</strong>
                </span>
                <span>
                  Output{' '}
                  <strong>{directionLabel(outputDirection, resolvedDirections.output)}</strong>
                </span>
                <span>
                  Controls <strong>{controls.length}</strong>
                </span>
              </div>

              <h3>Logical order</h3>
              <OrderStrip items={items} label="Logical character order" order="logical" />

              <h3>Visual reading order</h3>
              <OrderStrip
                items={visualItems.length > 0 ? visualItems : items}
                label="Visual reading order"
                order="visual"
              />
              <p className="hint">
                Visual order is measured from browser layout. Zero-width controls appear as labeled
                cells.
              </p>

              <h3>Escaped text</h3>
              <code className="bidi-helper-page__escaped">{escapedBidiText(text)}</code>

              <h3>Marker checks</h3>
              <div className="bidi-helper-page__diagnostics">
                {diagnostics.map((diagnostic) => (
                  <div
                    key={`${diagnostic.title}-${diagnostic.message}`}
                    className={`bidi-helper-page__diagnostic bidi-helper-page__diagnostic--${diagnostic.severity}`}
                  >
                    <strong>{diagnostic.title}</strong>
                    <span>{diagnostic.message}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section
            className="bidi-helper-page__lower-grid"
            aria-label="Isolation recipes and Unicode guide"
          >
            <div className="bidi-helper-page__panel">
              <h2>Isolation recipes</h2>
              <div className="bidi-helper-page__recipes">
                <div>
                  <strong>Plain auto</strong>
                  <code>{plainIsolateSnippet(text, 'auto')}</code>
                  <span>Unknown-direction text in a plain string.</span>
                </div>
                <div>
                  <strong>Plain LTR</strong>
                  <code>{plainIsolateSnippet(text, 'ltr')}</code>
                  <span>Known LTR value inside RTL text.</span>
                </div>
                <div>
                  <strong>Plain RTL</strong>
                  <code>{plainIsolateSnippet(text, 'rtl')}</code>
                  <span>Known RTL value inside LTR text.</span>
                </div>
                <div>
                  <strong>HTML auto</strong>
                  <code>{bdiSnippet(text, 'auto')}</code>
                  <span>Browser equivalent for unknown-direction inline content.</span>
                </div>
                <div>
                  <strong>HTML LTR</strong>
                  <code>{bdiSnippet(text, 'ltr')}</code>
                  <span>Browser equivalent for a known LTR span.</span>
                </div>
                <div>
                  <strong>HTML RTL</strong>
                  <code>{bdiSnippet(text, 'rtl')}</code>
                  <span>Browser equivalent for a known RTL span.</span>
                </div>
              </div>
            </div>

            <div className="bidi-helper-page__panel">
              <h2>Unicode guide</h2>
              <dl className="bidi-helper-page__guide">
                <dt>LRM / RLM / ALM</dt>
                <dd>Point marks. They affect nearby punctuation or boundaries and do not close.</dd>
                <dt>LRI ... PDI</dt>
                <dd>
                  Wrap known LTR spans such as brand names, URLs, IDs, or code inside RTL text.
                </dd>
                <dt>RLI ... PDI</dt>
                <dd>Wrap known RTL spans such as Hebrew or Arabic names inside LTR text.</dd>
                <dt>FSI ... PDI</dt>
                <dd>
                  Wrap unknown user text and let the first strong character choose the direction.
                </dd>
                <dt>Block direction</dt>
                <dd>
                  When the whole message is RTL, prefer an RTL block over one large inline isolate.
                </dd>
              </dl>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
