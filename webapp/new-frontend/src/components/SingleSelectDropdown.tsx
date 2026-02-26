import './chip-dropdown.css';
import './multi-select-chip.css';
import './single-select-dropdown.css';

import { createPortal } from 'react-dom';
import { type CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from 'react';

export type SingleSelectOption<T extends string | number> = {
  value: T;
  label: string;
  helper?: string;
};

export type SingleSelectFooterAction = {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  closeOnClick?: boolean;
};

type Props<T extends string | number> = {
  label: string;
  options: Array<SingleSelectOption<T>>;
  value: T | null;
  onChange: (next: T | null) => void;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
  align?: 'left' | 'right';
  buttonAriaLabel?: string;
  searchPlaceholder?: string;
  noResultsLabel?: string;
  noneLabel?: string;
  searchable?: boolean;
  getOptionClassName?: (option: SingleSelectOption<T>) => string | undefined;
  footerAction?: SingleSelectFooterAction | null;
};

export function SingleSelectDropdown<T extends string | number>({
  label,
  options,
  value,
  onChange,
  placeholder,
  className,
  disabled = false,
  align = 'left',
  buttonAriaLabel,
  searchPlaceholder,
  noResultsLabel,
  noneLabel,
  searchable = true,
  getOptionClassName,
  footerAction = null,
}: Props<T>) {
  const [isOpen, setIsOpen] = useState(false);
  const [filterQuery, setFilterQuery] = useState('');
  const containerRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();

  const updatePanelPosition = useCallback(() => {
    if (!buttonRef.current) {
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    const viewportPadding = 16;
    const gap = 8;
    const maxWidth = Math.min(448, window.innerWidth - viewportPadding * 2);

    setPanelStyle({
      position: 'fixed',
      top: Math.min(rect.bottom + gap, window.innerHeight - viewportPadding),
      left:
        align === 'right'
          ? undefined
          : Math.max(viewportPadding, Math.min(rect.left, window.innerWidth - maxWidth - viewportPadding)),
      right:
        align === 'right'
          ? Math.max(viewportPadding, window.innerWidth - rect.right)
          : undefined,
      minWidth: rect.width,
      maxWidth,
      zIndex: 1000,
    });
  }, [align]);

  useEffect(() => {
    if (disabled && isOpen) {
      setIsOpen(false);
      return;
    }
    if (!isOpen) {
      setFilterQuery('');
      return;
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!containerRef.current?.contains(target) && !panelRef.current?.contains(target)) {
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
  }, [disabled, isOpen, updatePanelPosition]);

  const normalizedOptions = useMemo(
    () => options.map((option) => ({ ...option, label: String(option.label) })),
    [options],
  );
  const selectedOption = normalizedOptions.find((option) => option.value === value) ?? null;
  const resolvedPlaceholder = placeholder ?? label;
  const summary = selectedOption ? selectedOption.label : resolvedPlaceholder;
  const isPlaceholder = !selectedOption;
  const isDisabled = disabled || (options.length === 0 && value === null);

  const visibleOptions = useMemo(() => {
    const query = filterQuery.trim().toLowerCase();
    if (!query) {
      return normalizedOptions;
    }
    return normalizedOptions.filter((option) => option.label.toLowerCase().includes(query));
  }, [filterQuery, normalizedOptions]);

  const filterInputPlaceholder = searchPlaceholder ?? `Filter ${label.toLowerCase()}`;
  const emptyLabel = noResultsLabel ?? 'No matches';
  const hasAnyPanelContent =
    normalizedOptions.length > 0 || noneLabel != null || footerAction != null;

  return (
    <div
      className={['chip-dropdown', className].filter(Boolean).join(' ')}
      ref={containerRef}
      data-align={align === 'right' ? 'right' : undefined}
    >
      <button
        type="button"
        className="chip-dropdown__button"
        onClick={() => setIsOpen((previous) => !previous)}
        disabled={isDisabled}
        aria-expanded={isOpen}
        aria-label={buttonAriaLabel ?? label}
        ref={buttonRef}
      >
        <span className={`chip-dropdown__summary${isPlaceholder ? ' is-placeholder' : ''}`}>
          {summary}
        </span>
        <span className="chip-dropdown__chevron" aria-hidden="true" />
      </button>
      {isOpen
        ? createPortal(
            <div className="chip-dropdown__panel" role="menu" ref={panelRef} style={panelStyle}>
          {hasAnyPanelContent ? (
            <>
              {normalizedOptions.length && searchable ? (
                <input
                  type="search"
                  value={filterQuery}
                  onChange={(event) => setFilterQuery(event.target.value)}
                  placeholder={filterInputPlaceholder}
                  className="multi-select-chip__search"
                />
              ) : null}
              {footerAction ? (
                <button
                  type="button"
                  className="single-select-dropdown__footer-action single-select-dropdown__footer-action--top"
                  onClick={() => {
                    footerAction.onClick();
                    if (footerAction.closeOnClick) {
                      setIsOpen(false);
                    }
                  }}
                  disabled={footerAction.disabled}
                >
                  {footerAction.label}
                </button>
              ) : null}
              <div className="single-select-dropdown__options">
                {noneLabel ? (
                  <button
                    type="button"
                    className={`single-select-dropdown__option${
                      value === null ? ' is-selected' : ''
                    }`}
                    onClick={() => {
                      onChange(null);
                      setIsOpen(false);
                    }}
                  >
                    {noneLabel}
                  </button>
                ) : null}
                {normalizedOptions.length ? (
                  visibleOptions.length ? (
                    visibleOptions.map((option) => (
                      <button
                        type="button"
                        key={String(option.value)}
                        className={[
                          'single-select-dropdown__option',
                          option.value === value ? 'is-selected' : '',
                          getOptionClassName?.(option) ?? '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                        onClick={() => {
                          onChange(option.value);
                          setIsOpen(false);
                        }}
                      >
                        <span className="single-select-dropdown__option-copy">
                          <span>{option.label}</span>
                          {option.helper ? (
                            <span className="single-select-dropdown__option-helper">
                              {option.helper}
                            </span>
                          ) : null}
                        </span>
                      </button>
                    ))
                  ) : (
                    <div className="single-select-dropdown__empty">{emptyLabel}</div>
                  )
                ) : (
                  <div className="single-select-dropdown__empty">{resolvedPlaceholder}</div>
                )}
              </div>
            </>
          ) : (
            <div className="single-select-dropdown__empty">{resolvedPlaceholder}</div>
          )}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
