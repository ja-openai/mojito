import './ai-review-speed-control.css';

import { type CSSProperties, useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import type { AiReviewPreset } from '../api/userPreferences';
import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

export type AiReviewSpeedControlProps = {
  value: AiReviewPreset;
  onChange: (value: AiReviewPreset) => void;
  disabled?: boolean;
  error?: string | null;
};

const speeds: { value: AiReviewPreset; label: string; description: string }[] = [
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
  value,
  onChange,
  disabled = false,
  error,
}: AiReviewSpeedControlProps) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(value);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const sliderRef = useRef<HTMLInputElement>(null);
  const lastCommitted = useRef(value);
  const panelId = useId();
  const descriptionId = useId();
  const selected = speeds.find((speed) => speed.value === value)!;
  const preview = speeds.find((speed) => speed.value === draft)!;

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
        className="ai-review-speed__button"
        disabled={disabled}
        aria-label={`Review speed: ${selected.label}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => {
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
                value={speeds.findIndex((speed) => speed.value === draft)}
                aria-disabled={disabled}
                aria-label="Review speed"
                aria-valuetext={preview.label}
                aria-describedby={descriptionId}
                onChange={(event) => {
                  if (!disabled) setDraft(speeds[event.currentTarget.valueAsNumber].value);
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
                <span>Ultra</span>
              </div>
              <p id={descriptionId}>{preview.description}</p>
              {error ? <div role="alert">{error}</div> : null}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}
