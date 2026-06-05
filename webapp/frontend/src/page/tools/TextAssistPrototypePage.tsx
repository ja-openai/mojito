import './text-assist-prototype-page.css';

import { useMemo, useRef, useState } from 'react';

import {
  VisibleTextEditor,
  type VisibleTextEditorHandle,
} from '../../components/VisibleTextEditor';
import {
  extractProtectedTextTokens,
  type ProtectedTextToken,
} from '../../utils/protectedTextTokens';
import { buildTextAssistWarnings } from '../../utils/textCharacters';

type DirectionMode = 'ltr' | 'rtl' | 'auto';
type ProtectionMode = 'none' | 'icu' | 'icu-html' | 'mf2-demo';

type Sample = {
  direction: DirectionMode;
  label: string;
  protectionMode: ProtectionMode;
  text: string;
};

const SAMPLES: Sample[] = [
  {
    label: 'Spaces + lines',
    text: '  Save  changes\nReview line breaks\nKeep trailing space ',
    direction: 'ltr',
    protectionMode: 'none',
  },
  {
    label: 'ICU placeholders',
    text: 'Hello {name}, you have {count, plural, one {# file} other {# files}}.',
    direction: 'ltr',
    protectionMode: 'icu',
  },
  {
    label: 'ICU + HTML tags',
    text: 'Read <termsOfUseLink>{count, plural, one {# term} other {# terms}}</termsOfUseLink>.',
    direction: 'ltr',
    protectionMode: 'icu-html',
  },
  {
    label: 'Non-breaking',
    text: 'Save\u00a0changes\nPrice: 12\u202f000',
    direction: 'ltr',
    protectionMode: 'none',
  },
  {
    label: 'MF2 demo',
    text: '.input {$count :number}\n.match {$count}\none {{1 file}}\n* {{# files}}',
    direction: 'ltr',
    protectionMode: 'mf2-demo',
  },
  {
    label: 'RTL placeholders',
    text: 'تم حفظ {filename}\n{count, plural, one {ملف واحد} other {# ملفات}}',
    direction: 'rtl',
    protectionMode: 'icu',
  },
];

const DEFAULT_SAMPLE = SAMPLES[0];

const directionOptions: Array<{ value: DirectionMode; label: string }> = [
  { value: 'ltr', label: 'LTR' },
  { value: 'rtl', label: 'RTL' },
  { value: 'auto', label: 'Auto' },
];

const protectionOptions: Array<{ value: ProtectionMode; label: string }> = [
  { value: 'none', label: 'Raw' },
  { value: 'icu', label: 'ICU' },
  { value: 'icu-html', label: 'ICU + HTML' },
  { value: 'mf2-demo', label: 'MF2 demo' },
];

export function TextAssistPrototypePage() {
  const [text, setText] = useState(DEFAULT_SAMPLE.text);
  const [showInvisibles, setShowInvisibles] = useState(true);
  const [direction, setDirection] = useState<DirectionMode>(DEFAULT_SAMPLE.direction);
  const [protectionMode, setProtectionMode] = useState<ProtectionMode>(
    DEFAULT_SAMPLE.protectionMode,
  );
  const editorRef = useRef<VisibleTextEditorHandle | null>(null);

  const warnings = useMemo(() => buildTextAssistWarnings(text, text), [text]);
  const protectedTokens: ProtectedTextToken[] = useMemo(
    () => extractProtectedTextTokens(text, protectionMode),
    [protectionMode, text],
  );

  const applySample = (label: string) => {
    const sample = SAMPLES.find((item) => item.label === label) ?? DEFAULT_SAMPLE;
    setText(sample.text);
    setDirection(sample.direction);
    setProtectionMode(sample.protectionMode);
    window.requestAnimationFrame(() => editorRef.current?.focus());
  };

  return (
    <div className="page-wrapper text-assist-prototype">
      <div className="text-assist-prototype__shell">
        <header className="text-assist-prototype__header">
          <h1 className="page-title">Visible Text Editor Prototype</h1>
        </header>

        <div className="text-assist-prototype__editor">
          <div className="text-assist-prototype__toolbar" aria-label="Text controls">
            <select
              className="input text-assist-prototype__select"
              value={SAMPLES.find((sample) => sample.text === text)?.label ?? ''}
              onChange={(event) => applySample(event.target.value)}
              aria-label="Sample"
            >
              <option value="" disabled>
                Custom
              </option>
              {SAMPLES.map((sample) => (
                <option key={sample.label} value={sample.label}>
                  {sample.label}
                </option>
              ))}
            </select>

            <select
              className="input text-assist-prototype__select text-assist-prototype__select--short"
              value={direction}
              onChange={(event) => setDirection(event.target.value as DirectionMode)}
              aria-label="Direction"
            >
              {directionOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <select
              className="input text-assist-prototype__select"
              value={protectionMode}
              onChange={(event) => setProtectionMode(event.target.value as ProtectionMode)}
              aria-label="Placeholder protection"
            >
              {protectionOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>

          <VisibleTextEditor
            ref={editorRef}
            ariaLabel="Text"
            className="text-assist-prototype__text-editor"
            controlBar={{
              onToggleInvisibles: () => setShowInvisibles((current) => !current),
              protectedTokenCount: protectedTokens.length,
            }}
            dir={direction}
            onChange={setText}
            placeholder="Type text"
            protectedTokens={protectedTokens}
            showInvisibles={showInvisibles}
            spellCheck
            value={text}
          />

          <div className="text-assist-prototype__status-row">
            {warnings.length > 0 ? (
              warnings.map((warning, index) => (
                <span key={`${warning.code}-${index}`} className="text-assist-prototype__warning">
                  <strong>{warning.code}</strong>
                  {warning.message}
                </span>
              ))
            ) : (
              <span className="text-assist-prototype__ok">No deterministic warnings</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
