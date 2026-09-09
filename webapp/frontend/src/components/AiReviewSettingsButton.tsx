import './ai-review-settings.css';

import { type CSSProperties, useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import type { AiReviewSettings } from '../hooks/useAiReviewPreferences';
import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

export function AiReviewSettingsButton({ settings }: { settings: AiReviewSettings }) {
  const [open, setOpen] = useState(false);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;

    const reposition = () => {
      if (!buttonRef.current) return;
      setPanelStyle(
        getAnchoredDropdownPanelStyle({
          rect: buttonRef.current.getBoundingClientRect(),
          align: 'left',
          maxWidth: Math.min(256, window.innerWidth - 32),
          panelHeight: panelRef.current?.offsetHeight ?? 0,
        }),
      );
    };
    const isInside = (target: EventTarget | null) =>
      target instanceof Node &&
      (buttonRef.current?.contains(target) || panelRef.current?.contains(target));
    const dismissOutside = (event: Event) => {
      if (!isInside(event.target)) setOpen(false);
    };
    const dismissEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setOpen(false);
        buttonRef.current?.focus();
      }
    };

    reposition();
    panelRef.current?.focus();
    window.addEventListener('pointerdown', dismissOutside);
    window.addEventListener('focusin', dismissOutside);
    window.addEventListener('keydown', dismissEscape, true);
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    return () => {
      window.removeEventListener('pointerdown', dismissOutside);
      window.removeEventListener('focusin', dismissOutside);
      window.removeEventListener('keydown', dismissEscape, true);
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition, true);
    };
  }, [open]);

  return (
    <>
      <button
        type="button"
        ref={buttonRef}
        className={`ai-review-settings__button${settings.error ? ' has-error' : ''}`}
        aria-label="AI review settings"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        title={settings.error ? 'AI review settings need attention' : 'AI review settings'}
        onClick={() => setOpen((current) => !current)}
      >
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M10 2h4l.7 3.1 2.2 1.3 3-1 2 3.4-2.3 2.1v2.2l2.3 2.1-2 3.4-3-1-2.2 1.3L14 22h-4l-.7-3.1-2.2-1.3-3 1-2-3.4 2.3-2.1v-2.2L2.1 8.4l2-3.4 3 1 2.2-1.3Z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      </button>
      {open
        ? createPortal(
            <div
              ref={panelRef}
              id={panelId}
              role="dialog"
              aria-label="AI review settings"
              tabIndex={-1}
              className="ai-review-settings__panel"
              style={panelStyle}
              onKeyDown={(event) => event.stopPropagation()}
            >
              <label>
                <input
                  type="checkbox"
                  checked={!settings.automaticDisabled}
                  disabled={!settings.ready || settings.isSaving}
                  onChange={(event) => settings.onChangeAutomaticDisabled(!event.target.checked)}
                />
                Review automatically
              </label>
              {settings.error ? (
                <div role="alert">
                  {settings.error}
                  {!settings.ready ? (
                    <button type="button" onClick={settings.onRetryLoad}>
                      Try again
                    </button>
                  ) : null}
                </div>
              ) : null}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}
