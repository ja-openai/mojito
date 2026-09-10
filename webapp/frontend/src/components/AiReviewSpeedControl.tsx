import './ai-review-speed-control.css';

import { type CSSProperties, useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import type { AiReviewPreset, AiReviewStyle } from '../api/userPreferences';
import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

export type AiReviewSpeedControlProps = {
  value: AiReviewPreset;
  allowExtendedPresets?: boolean;
  onChange: (value: AiReviewPreset) => void;
  automaticDisabled: boolean;
  onChangeAutomaticDisabled: (disabled: boolean) => void;
  reviewStyle?: AiReviewStyle;
  onChangeReviewStyle?: (style: AiReviewStyle) => void;
  showScore?: boolean;
  onChangeShowScore?: (show: boolean) => void;
  disabled?: boolean;
  error?: string | null;
  onRetry?: () => void;
};

const allSpeeds: { value: AiReviewPreset; label: string; description: string }[] = [
  { value: 'fastest', label: 'Fastest', description: 'Prioritize a quick response.' },
  { value: 'fast', label: 'Fast', description: 'Favor a quicker review.' },
  { value: 'balanced', label: 'Balanced', description: 'Allow some time for review.' },
  { value: 'thorough', label: 'Thorough', description: 'Allow more time for review.' },
  { value: 'deep', label: 'Deep', description: 'Allow extended time for review.' },
  { value: 'ultra', label: 'Ultra', description: 'Allow the most time for review.' },
];
const rangeKeys = new Set([
  'ArrowLeft',
  'ArrowRight',
  'ArrowUp',
  'ArrowDown',
  'Home',
  'End',
  'PageUp',
  'PageDown',
]);

export function AiReviewSpeedControl({
  value: savedValue,
  allowExtendedPresets = false,
  onChange,
  automaticDisabled,
  onChangeAutomaticDisabled,
  reviewStyle = 'corrections_and_alternatives',
  onChangeReviewStyle,
  showScore = true,
  onChangeShowScore,
  disabled = false,
  error,
  onRetry,
}: AiReviewSpeedControlProps) {
  const speeds = allowExtendedPresets ? allSpeeds : allSpeeds.slice(0, 3);
  const value = speeds.some((speed) => speed.value === savedValue) ? savedValue : 'balanced';
  const automaticUltraFallback = value === 'ultra' && !automaticDisabled;
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(value);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const sliderRef = useRef<HTMLInputElement>(null);
  const lastCommitted = useRef(value);
  const panelId = useId();
  const descriptionId = useId();
  const automaticStatusId = useId();
  const automaticDescriptionId = useId();
  const selected = speeds.find((speed) => speed.value === value)!;
  const preview = speeds.find((speed) => speed.value === draft) ?? selected;

  useEffect(() => {
    setDraft(value);
    lastCommitted.current = value;
  }, [value, error]);

  useEffect(() => {
    if (!open) return;
    const reposition = () => {
      if (!buttonRef.current) return;
      setPanelStyle(
        getAnchoredDropdownPanelStyle({
          rect: buttonRef.current.getBoundingClientRect(),
          align: 'left',
          maxWidth: Math.min(272, window.innerWidth - 32),
          panelHeight: panelRef.current?.offsetHeight ?? 0,
        }),
      );
    };
    const dismissOutside = (event: Event) => {
      if (
        event.target instanceof Node &&
        !buttonRef.current?.contains(event.target) &&
        !panelRef.current?.contains(event.target)
      ) {
        setOpen(false);
      }
    };
    const dismissEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    reposition();
    sliderRef.current?.focus();
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

  const commit = (index: number) => {
    const next = speeds[index]?.value;
    if (disabled || !next || next === lastCommitted.current) return;
    lastCommitted.current = next;
    onChange(next);
  };

  return (
    <>
      <button
        type="button"
        ref={buttonRef}
        className={`ai-review-speed__button${error ? ' has-error' : ''}`}
        aria-disabled={disabled && !error}
        title={
          error ??
          (automaticDisabled
            ? 'Automatic review paused.'
            : automaticUltraFallback
              ? 'Automatic reviews use Balanced. Ultra is available for manual requests.'
              : 'Review speed')
        }
        aria-label={`Review speed: ${selected.label}`}
        aria-describedby={
          automaticDisabled || automaticUltraFallback ? automaticStatusId : undefined
        }
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => {
          if (disabled && !error) return;
          setDraft(value);
          lastCommitted.current = value;
          setOpen((current) => !current);
        }}
      >
        <svg
          width="14"
          height="14"
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M11.5 2 4 11h5l-.5 7L16 9h-5l.5-7Z" />
        </svg>
        <span>{selected.label}</span>
        {automaticDisabled || automaticUltraFallback ? (
          <span id={automaticStatusId} className="ai-review-speed__automatic-status">
            {automaticDisabled ? 'Auto off' : 'Auto: Balanced'}
          </span>
        ) : null}
        <svg
          width="10"
          height="10"
          viewBox="0 0 12 12"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          aria-hidden="true"
        >
          <path d="m3 4.5 3 3 3-3" />
        </svg>
      </button>
      {open
        ? createPortal(
            <div
              ref={panelRef}
              id={panelId}
              role="dialog"
              aria-label="Review speed"
              className="ai-review-speed__panel"
              style={panelStyle}
              onKeyDown={(event) => event.stopPropagation()}
              onKeyUp={(event) => event.stopPropagation()}
            >
              <div className="ai-review-speed__heading">
                <span>Review speed</span>
                <strong>{preview.label}</strong>
              </div>
              <input
                ref={sliderRef}
                type="range"
                min={0}
                max={speeds.length - 1}
                step={1}
                value={speeds.findIndex((speed) => speed.value === preview.value)}
                aria-disabled={disabled}
                aria-label="Review speed"
                aria-valuetext={preview.label}
                aria-describedby={descriptionId}
                onChange={(event) => {
                  const next = speeds[event.currentTarget.valueAsNumber]?.value;
                  if (!disabled && next) setDraft(next);
                }}
                onPointerDown={(event) => {
                  if (disabled) event.preventDefault();
                  else event.currentTarget.setPointerCapture?.(event.pointerId);
                }}
                onPointerUp={(event) => commit(event.currentTarget.valueAsNumber)}
                onPointerCancel={() => setDraft(value)}
                onKeyDown={(event) => {
                  if (disabled && rangeKeys.has(event.key)) event.preventDefault();
                }}
                onKeyUp={(event) => {
                  if (rangeKeys.has(event.key)) commit(event.currentTarget.valueAsNumber);
                }}
              />
              <div className="ai-review-speed__ticks" aria-hidden="true">
                {speeds.map((speed) => (
                  <span
                    key={speed.value}
                    className={speed.value === draft ? 'is-selected' : undefined}
                  />
                ))}
              </div>
              <div className="ai-review-speed__stops" aria-hidden="true">
                <span>Fastest</span>
                <span>{speeds[speeds.length - 1].label}</span>
              </div>
              <p id={descriptionId}>{preview.description}</p>
              {allowExtendedPresets && preview.value === 'ultra' ? (
                <p>
                  Ultra is temporarily available for manual requests only. Automatic reviews use
                  Balanced; your saved Ultra selection stays available for Review and Ask.
                </p>
              ) : null}
              {!allowExtendedPresets ? (
                <p>
                  Thorough, Deep, and Ultra are reserved for admins to keep the review queue moving.
                </p>
              ) : null}
              <div className="ai-review-speed__style">
                <label>
                  <span>Review style</span>
                  <select
                    value={reviewStyle}
                    disabled={disabled}
                    onChange={(event) =>
                      onChangeReviewStyle?.(event.currentTarget.value as AiReviewStyle)
                    }
                  >
                    <option value="corrections_only">Corrections only</option>
                    <option value="corrections_and_alternatives">Corrections + alternatives</option>
                  </select>
                </label>
                <p>
                  {reviewStyle === 'corrections_only'
                    ? 'Suggest fixes for identified issues.'
                    : 'Suggest fixes and offer alternative wording when useful.'}
                </p>
              </div>
              <div className="ai-review-speed__automatic">
                <label>
                  <input
                    type="checkbox"
                    checked={!automaticDisabled}
                    aria-disabled={disabled}
                    aria-describedby={automaticDisabled ? automaticDescriptionId : undefined}
                    onKeyDown={(event) => {
                      if (disabled && event.key === ' ') event.preventDefault();
                    }}
                    onChange={(event) => {
                      if (!disabled) onChangeAutomaticDisabled(!event.currentTarget.checked);
                    }}
                  />
                  <span>Automatic review</span>
                </label>
                {automaticDisabled ? (
                  <p id={automaticDescriptionId}>Automatic review paused.</p>
                ) : null}
                <div className="ai-review-speed__score-option">
                  <label>
                    <input
                      type="checkbox"
                      checked={showScore}
                      disabled={disabled}
                      onChange={(event) => onChangeShowScore?.(event.currentTarget.checked)}
                    />
                    <span>Show score</span>
                  </label>
                  <ScoreHelp />
                </div>
              </div>
              {error ? (
                <div role="alert">
                  {error}
                  {onRetry ? (
                    <button type="button" onClick={onRetry}>
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

function ScoreHelp() {
  const id = useId();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);

  return (
    <span
      className="ai-review-speed__score-help"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => {
        if (document.activeElement !== buttonRef.current) setOpen(false);
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        className="ai-review-speed__score-info"
        aria-label="About model confidence"
        aria-describedby={open ? id : undefined}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onClick={() => setOpen(true)}
      >
        <svg viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeWidth="1.4" />
          <path d="M10 9v5" stroke="currentColor" strokeWidth="1.4" />
          <circle cx="10" cy="6" r="1" fill="currentColor" />
        </svg>
      </button>
      {open ? (
        <span id={id} role="tooltip" className="ai-review-speed__score-tooltip">
          The model's self-reported confidence in its wording, from 0 to 100. This is not a
          translation quality rating or a measured probability of correctness. A high score can
          still accompany an error.
        </span>
      ) : null}
    </span>
  );
}
