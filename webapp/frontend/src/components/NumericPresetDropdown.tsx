import './chip-dropdown.css';
import './numeric-preset-dropdown.css';

import { type CSSProperties, useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

type PresetOption = {
  value: number;
  label: string;
};

type Props = {
  value: number;
  buttonLabel: string;
  menuLabel: string;
  presetOptions: PresetOption[];
  onChange: (value: number) => void;
  disabled?: boolean;
  ariaLabel?: string;
  className?: string;
  buttonClassName?: string;
  panelClassName?: string;
  pillsClassName?: string;
  optionClassName?: string;
  optionActiveClassName?: string;
  customClassName?: string;
  customActiveClassName?: string;
  customLabelClassName?: string;
  customInputClassName?: string;
  customButtonClassName?: string;
  customButtonLabel?: string;
  customChipLabel?: string;
  customInitialValue?: number;
};

function cx(...values: Array<string | false | null | undefined>) {
  return values.filter(Boolean).join(' ');
}

export function NumericPresetDropdown({
  value,
  buttonLabel,
  menuLabel,
  presetOptions,
  onChange,
  disabled = false,
  ariaLabel,
  className,
  buttonClassName,
  panelClassName,
  pillsClassName,
  optionClassName,
  optionActiveClassName,
  customClassName,
  customActiveClassName,
  customLabelClassName,
  customInputClassName,
  customButtonClassName,
  customButtonLabel = 'Custom…',
  customChipLabel = 'Custom',
  customInitialValue,
}: Props) {
  const [isOpen, setIsOpen] = useState(false);
  const [draft, setDraft] = useState(String(value));
  const [showCustomInput, setShowCustomInput] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();

  const isPreset = presetOptions.some((option) => option.value === value);
  const isCustomActive = showCustomInput || !isPreset;

  const commitDraft = useCallback(() => {
    const trimmed = draft.trim();
    if (!trimmed) {
      setDraft(String(value));
      return;
    }
    const next = parseInt(trimmed, 10);
    if (Number.isNaN(next) || next < 1) {
      setDraft(String(value));
      return;
    }
    if (next !== value) {
      onChange(next);
    }
  }, [draft, onChange, value]);

  const updatePanelPosition = useCallback(() => {
    if (!buttonRef.current) {
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    const viewportPadding = 16;
    const gap = 8;
    const maxWidth = Math.min(440, window.innerWidth - viewportPadding * 2);
    const align = rect.left + maxWidth > window.innerWidth - viewportPadding ? 'right' : 'left';

    setPanelStyle(
      getAnchoredDropdownPanelStyle({
        rect,
        align,
        viewportPadding,
        gap,
        maxWidth,
      }),
    );
  }, []);

  useEffect(() => {
    if (disabled && isOpen) {
      if (isCustomActive) {
        commitDraft();
      }
      setIsOpen(false);
    }
  }, [commitDraft, disabled, isCustomActive, isOpen]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    setDraft(String(value));
    setShowCustomInput(false);
  }, [isOpen, value]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!containerRef.current?.contains(target) && !panelRef.current?.contains(target)) {
        if (isCustomActive) {
          commitDraft();
        }
        setIsOpen(false);
      }
    };
    const handleReposition = () => updatePanelPosition();

    updatePanelPosition();
    window.addEventListener('pointerdown', handlePointerDown);
    window.addEventListener('resize', handleReposition);
    window.addEventListener('scroll', handleReposition, true);
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown);
      window.removeEventListener('resize', handleReposition);
      window.removeEventListener('scroll', handleReposition, true);
    };
  }, [commitDraft, isCustomActive, isOpen, updatePanelPosition]);

  return (
    <div className={cx('chip-dropdown numeric-preset-dropdown', className)} ref={containerRef}>
      <button
        type="button"
        className={cx('numeric-preset-dropdown__button', buttonClassName)}
        onClick={() => setIsOpen((previous) => !previous)}
        aria-expanded={isOpen}
        aria-label={ariaLabel}
        disabled={disabled}
        ref={buttonRef}
      >
        <span>{buttonLabel}</span>
        <span className="chip-dropdown__chevron" aria-hidden="true" />
      </button>
      {isOpen
        ? createPortal(
            <div
              className={cx('chip-dropdown__panel numeric-preset-dropdown__panel', panelClassName)}
              role="menu"
              ref={panelRef}
              style={panelStyle}
            >
              <div className="numeric-preset-dropdown__section">
                <div className="numeric-preset-dropdown__label">{menuLabel}</div>
                <div className={cx('numeric-preset-dropdown__pills', pillsClassName)}>
                  {presetOptions.map((option) => (
                    <button
                      type="button"
                      key={option.value}
                      className={cx(
                        'numeric-preset-dropdown__option',
                        optionClassName,
                        option.value === value && 'is-active',
                        option.value === value && optionActiveClassName,
                      )}
                      onClick={() => onChange(option.value)}
                    >
                      {option.label}
                    </button>
                  ))}
                  {isCustomActive ? (
                    <div
                      className={cx(
                        'numeric-preset-dropdown__custom',
                        customClassName,
                        'is-active',
                        customActiveClassName,
                      )}
                    >
                      <span
                        className={cx(
                          'numeric-preset-dropdown__custom-label',
                          customLabelClassName,
                        )}
                      >
                        {customChipLabel}
                      </span>
                      <input
                        ref={inputRef}
                        className={cx(
                          'numeric-preset-dropdown__custom-input',
                          customInputClassName,
                        )}
                        type="number"
                        inputMode="numeric"
                        min={1}
                        value={draft}
                        onChange={(event) => setDraft(event.target.value)}
                        onBlur={() => {
                          commitDraft();
                        }}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter') {
                            commitDraft();
                          }
                          if (event.key === 'Escape') {
                            setDraft(String(value));
                            setShowCustomInput(false);
                          }
                        }}
                      />
                    </div>
                  ) : (
                    <button
                      type="button"
                      className={cx(
                        'numeric-preset-dropdown__option',
                        optionClassName,
                        customButtonClassName,
                      )}
                      onClick={() => {
                        setShowCustomInput(true);
                        setDraft(String(customInitialValue ?? value));
                        queueMicrotask(() => {
                          inputRef.current?.focus();
                          inputRef.current?.select();
                        });
                      }}
                    >
                      {customButtonLabel}
                    </button>
                  )}
                </div>
              </div>
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
