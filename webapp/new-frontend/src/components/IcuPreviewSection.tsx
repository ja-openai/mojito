import './icu-preview-section.css';

import { useMemo } from 'react';

import {
  getMissingIcuSourceParameters,
  hasIcuParameters,
  type IcuPreviewMode,
  resolveIcuPreviewMode,
} from '../utils/icuPreview';
import { IcuMessagePreview } from './IcuMessagePreview';

type IcuPreviewSectionProps = {
  sourceMessage?: string | null;
  targetMessage?: string | null;
  targetLocale: string;
  mode: IcuPreviewMode;
  isCollapsed: boolean;
  onToggleCollapsed: () => void;
  onChangeMode: (mode: IcuPreviewMode) => void;
  className?: string;
  titleClassName?: string;
};

export function IcuPreviewSection({
  sourceMessage,
  targetMessage,
  targetLocale,
  mode,
  isCollapsed,
  onToggleCollapsed,
  onChangeMode,
  className,
  titleClassName,
}: IcuPreviewSectionProps) {
  const hasIcuSource = useMemo(() => hasIcuParameters(sourceMessage), [sourceMessage]);
  const hasIcuTarget = useMemo(() => hasIcuParameters(targetMessage), [targetMessage]);
  const missingSourceParameters = useMemo(
    () => getMissingIcuSourceParameters(sourceMessage, targetMessage),
    [sourceMessage, targetMessage],
  );
  const hasIcuMessage = hasIcuSource || hasIcuTarget;
  const selectedMode = resolveIcuPreviewMode(mode, hasIcuSource, hasIcuTarget);
  const selectedMessage = selectedMode === 'source' ? (sourceMessage ?? '') : (targetMessage ?? '');
  const selectedLocale = selectedMode === 'source' ? 'en' : targetLocale;

  if (!hasIcuMessage) {
    return null;
  }

  return (
    <section className={className ? `icu-preview-section ${className}` : 'icu-preview-section'}>
      <div className="icu-preview-section__header-row">
        <button
          type="button"
          className="icu-preview-section__header"
          onClick={onToggleCollapsed}
          aria-expanded={!isCollapsed}
        >
          <span className="icu-preview-section__heading">
            <span
              className={
                titleClassName
                  ? `icu-preview-section__title ${titleClassName}`
                  : 'icu-preview-section__title'
              }
            >
              ICU preview
            </span>
            {missingSourceParameters.length > 0 ? (
              <span className="icu-preview-section__summary">
                Missing in target: {missingSourceParameters.map((name) => `{${name}}`).join(', ')}
              </span>
            ) : null}
          </span>
        </button>
        <div className="icu-preview-section__controls">
          <div className="icu-preview-section__toggle-group" role="group" aria-label="ICU message">
            <button
              type="button"
              className={`icu-preview-section__toggle-option ${
                selectedMode === 'target' ? 'is-active' : ''
              }`}
              onClick={() => onChangeMode('target')}
              disabled={!hasIcuTarget}
            >
              Target
            </button>
            <button
              type="button"
              className={`icu-preview-section__toggle-option ${
                selectedMode === 'source' ? 'is-active' : ''
              }`}
              onClick={() => onChangeMode('source')}
              disabled={!hasIcuSource}
            >
              Source
            </button>
          </div>
        </div>
        <button
          type="button"
          className="icu-preview-section__action"
          onClick={onToggleCollapsed}
          aria-expanded={!isCollapsed}
        >
          {isCollapsed ? 'Show' : 'Hide'}
        </button>
      </div>
      {!isCollapsed ? (
        <div className="icu-preview-section__content">
          <IcuMessagePreview
            message={selectedMessage}
            locale={selectedLocale}
            showMessageEditor={false}
            showLocaleInput={false}
            showExamples={false}
          />
        </div>
      ) : null}
    </section>
  );
}
