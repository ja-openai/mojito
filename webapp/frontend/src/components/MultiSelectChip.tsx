import './chip-dropdown.css';
import './multi-select-chip.css';
import './single-select-dropdown.css';

import {
  type CSSProperties,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { createPortal } from 'react-dom';

import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

export type MultiSelectOption<T extends string | number> = {
  value: T;
  label: string;
  secondaryLabel?: string;
  searchText?: string;
};

export type MultiSelectCustomAction = {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  active?: boolean;
  ariaLabel?: string;
};

type SummaryFormatter<T extends string | number> = (args: {
  label: string;
  options: Array<MultiSelectOption<T>>;
  selectedValues: T[];
}) => string;

export type MultiSelectChipProps<T extends string | number> = {
  label: string;
  options: Array<MultiSelectOption<T>>;
  selectedValues: T[];
  onChange: (next: T[]) => void;
  selectionMode?: 'multiple' | 'single';
  placeholder: string;
  emptyOptionsLabel: string;
  className?: string;
  align?: 'left' | 'right';
  disabled?: boolean;
  buttonAriaLabel?: string;
  searchPlaceholder?: string;
  noResultsLabel?: string;
  selectAllLabel?: string;
  clearAllLabel?: string;
  onlyLabel?: string;
  summaryFormatter?: SummaryFormatter<T>;
  customActions?: MultiSelectCustomAction[];
  quickActions?: MultiSelectCustomAction[];
};

export function MultiSelectChip<T extends string | number>({
  label,
  options,
  selectedValues,
  onChange,
  selectionMode = 'multiple',
  placeholder,
  emptyOptionsLabel,
  className,
  align = 'left',
  disabled = false,
  buttonAriaLabel,
  searchPlaceholder,
  noResultsLabel,
  selectAllLabel,
  clearAllLabel,
  onlyLabel,
  summaryFormatter,
  customActions,
  quickActions,
}: MultiSelectChipProps<T>) {
  const singleSelection = selectionMode === 'single';
  const radioGroupName = useId();
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const filterInputRef = useRef<HTMLInputElement | null>(null);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();
  const [filterQuery, setFilterQuery] = useState('');

  const updatePanelPosition = useCallback(() => {
    if (!buttonRef.current) {
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    const viewportPadding = 16;
    const gap = 8;
    const maxWidth = Math.min(512, window.innerWidth - viewportPadding * 2);

    setPanelStyle(
      getAnchoredDropdownPanelStyle({
        rect,
        align,
        viewportPadding,
        gap,
        maxWidth,
        panelHeight: panelRef.current?.getBoundingClientRect().height,
      }),
    );
  }, [align]);

  useLayoutEffect(() => {
    if (!isOpen) {
      return;
    }
    updatePanelPosition();

    if (!panelRef.current || typeof ResizeObserver === 'undefined') {
      return;
    }

    const observer = new ResizeObserver(() => {
      updatePanelPosition();
    });
    observer.observe(panelRef.current);

    return () => observer.disconnect();
  }, [isOpen, updatePanelPosition]);

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

    window.addEventListener('pointerdown', handlePointerDown);
    window.addEventListener('resize', handleReposition);
    window.addEventListener('scroll', handleReposition, true);
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown);
      window.removeEventListener('resize', handleReposition);
      window.removeEventListener('scroll', handleReposition, true);
    };
  }, [disabled, isOpen, updatePanelPosition]);

  useEffect(() => {
    if (!isOpen || !options.length) {
      return;
    }

    queueMicrotask(() => {
      const input = filterInputRef.current;
      if (!input) {
        return;
      }
      input.focus();
      input.select();
    });
  }, [isOpen, options.length]);

  const selectedSet = new Set(selectedValues);

  const filterInputPlaceholder = (() => {
    if (searchPlaceholder) {
      return searchPlaceholder;
    }
    if (label?.length) {
      return `Filter ${label.toLowerCase()}`;
    }
    return 'Filter options';
  })();

  const summary = summaryFormatter
    ? summaryFormatter({ label, options, selectedValues })
    : (() => {
        if (!options.length) {
          return emptyOptionsLabel;
        }
        if (!selectedValues.length) {
          return placeholder;
        }
        if (singleSelection) {
          return options.find((option) => selectedSet.has(option.value))?.label ?? placeholder;
        }
        if (selectedValues.length === options.length) {
          return `All ${label.toLowerCase()}`;
        }
        if (selectedValues.length <= 2) {
          return options
            .filter((option) => selectedSet.has(option.value))
            .map((option) => option.label)
            .join(', ');
        }
        return `${selectedValues.length} selected`;
      })();

  const isPlaceholder = options.length > 0 && selectedValues.length === 0;

  const toggleValue = (value: T) => {
    if (singleSelection) {
      onChange([value]);
      setIsOpen(false);
      buttonRef.current?.focus();
      return;
    }
    if (selectedSet.has(value)) {
      onChange(selectedValues.filter((item) => item !== value));
      return;
    }
    onChange([...selectedValues, value]);
  };

  const visibleOptions = filterQuery
    ? options.filter((option) =>
        (option.searchText ?? option.label)
          .toLowerCase()
          .includes(filterQuery.trim().toLowerCase()),
      )
    : options;

  const allVisibleSelected =
    visibleOptions.length > 0 && visibleOptions.every((option) => selectedSet.has(option.value));

  const selectAll = () => {
    if (!visibleOptions.length) {
      return;
    }
    const next = [...selectedValues];
    const nextSet = new Set(selectedValues);
    visibleOptions.forEach((option) => {
      if (!nextSet.has(option.value)) {
        next.push(option.value);
        nextSet.add(option.value);
      }
    });
    onChange(next);
  };

  const clearAll = () => {
    onChange([]);
  };

  const resolvedClassName = ['chip-dropdown', 'chip-dropdown--xs', 'multi-select-chip', className]
    .filter(Boolean)
    .join(' ');
  const resolvedButtonAriaLabel = buttonAriaLabel ?? label;
  const noResultsText = noResultsLabel ?? 'No matches';
  const selectAllText = selectAllLabel ?? 'Select all';
  const clearAllText = clearAllLabel ?? 'Clear';
  const onlyText = onlyLabel ?? 'Only';
  const resolvedCustomActions = singleSelection ? [] : (customActions ?? []);
  const resolvedQuickActions = singleSelection ? [] : (quickActions ?? []);
  const quickActionStrip = resolvedQuickActions.length ? (
    <div className="multi-select-chip__actions multi-select-chip__actions--strip">
      {resolvedQuickActions.map((action) => (
        <button
          type="button"
          key={action.label}
          className={`multi-select-chip__action-button multi-select-chip__action-button--strip${
            action.active ? ' is-active' : ''
          }`}
          onClick={action.onClick}
          disabled={Boolean(action.disabled)}
          aria-label={action.ariaLabel ?? action.label}
          aria-pressed={Boolean(action.active)}
        >
          {action.label}
        </button>
      ))}
    </div>
  ) : null;
  const canOpen =
    !disabled &&
    (options.length > 0 || resolvedCustomActions.length > 0 || resolvedQuickActions.length > 0);

  return (
    <div
      className={resolvedClassName}
      ref={containerRef}
      data-align={align === 'right' ? 'right' : undefined}
      onKeyDown={(event) => {
        if (singleSelection && isOpen && event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          setIsOpen(false);
          buttonRef.current?.focus();
        }
      }}
    >
      <button
        type="button"
        className="chip-dropdown__button"
        onClick={() => setIsOpen((previous) => !previous)}
        disabled={!canOpen}
        aria-expanded={isOpen}
        aria-label={resolvedButtonAriaLabel}
        ref={buttonRef}
        onPointerDown={() => {
          if (!isOpen) {
            updatePanelPosition();
          }
        }}
      >
        <span className={`chip-dropdown__summary${isPlaceholder ? ' is-placeholder' : ''}`}>
          {summary}
        </span>
        <span className="chip-dropdown__chevron" aria-hidden="true" />
      </button>
      {isOpen
        ? createPortal(
            <div
              className={`chip-dropdown__panel${
                resolvedQuickActions.length ? ' multi-select-chip__panel--with-strip' : ''
              }`}
              role="menu"
              ref={panelRef}
              style={panelStyle}
            >
              {options.length ? (
                <>
                  <input
                    ref={filterInputRef}
                    type="search"
                    value={filterQuery}
                    onChange={(event) => setFilterQuery(event.target.value)}
                    placeholder={filterInputPlaceholder}
                    className="multi-select-chip__search"
                  />
                  {!singleSelection &&
                    (quickActionStrip ?? (
                      <div className="multi-select-chip__actions">
                        <button
                          type="button"
                          className="multi-select-chip__action-button"
                          onClick={selectAll}
                          disabled={allVisibleSelected}
                        >
                          {selectAllText}
                        </button>
                        <button
                          type="button"
                          className="multi-select-chip__action-button"
                          onClick={clearAll}
                          disabled={selectedValues.length === 0}
                        >
                          {clearAllText}
                        </button>
                      </div>
                    ))}
                  {resolvedCustomActions.length ? (
                    <div className="multi-select-chip__actions multi-select-chip__actions--secondary">
                      {resolvedCustomActions.map((action) => (
                        <button
                          type="button"
                          key={action.label}
                          className="multi-select-chip__action-button multi-select-chip__action-button--link"
                          onClick={action.onClick}
                          disabled={Boolean(action.disabled)}
                          aria-label={action.ariaLabel ?? action.label}
                        >
                          {action.label}
                        </button>
                      ))}
                    </div>
                  ) : null}
                  <div
                    className="multi-select-chip__options"
                    role={singleSelection ? 'radiogroup' : undefined}
                    aria-label={singleSelection ? label : undefined}
                  >
                    {visibleOptions.length ? (
                      visibleOptions.map((option) => {
                        const checked = selectedSet.has(option.value);
                        return (
                          <label
                            key={String(option.value)}
                            className={[
                              'multi-select-chip__option',
                              singleSelection &&
                                'multi-select-chip__option--single single-select-dropdown__option',
                              singleSelection && checked && 'is-selected',
                            ]
                              .filter(Boolean)
                              .join(' ')}
                          >
                            <input
                              type={singleSelection ? 'radio' : 'checkbox'}
                              name={singleSelection ? radioGroupName : undefined}
                              aria-label={
                                singleSelection
                                  ? [option.label, option.secondaryLabel].filter(Boolean).join(' ')
                                  : undefined
                              }
                              checked={checked}
                              onChange={() => toggleValue(option.value)}
                            />
                            <span className="multi-select-chip__option-label">
                              <span className="multi-select-chip__option-primary">
                                {option.label}
                              </span>
                              {option.secondaryLabel ? (
                                <span className="multi-select-chip__option-secondary">
                                  {option.secondaryLabel}
                                </span>
                              ) : null}
                            </span>
                            {!singleSelection && (
                              <button
                                type="button"
                                className="multi-select-chip__only"
                                onClick={(event) => {
                                  event.preventDefault();
                                  event.stopPropagation();
                                  onChange([option.value]);
                                }}
                              >
                                {onlyText}
                              </button>
                            )}
                          </label>
                        );
                      })
                    ) : (
                      <div className="multi-select-chip__empty">{noResultsText}</div>
                    )}
                  </div>
                </>
              ) : (
                <>
                  {quickActionStrip}
                  {resolvedCustomActions.length ? (
                    <div className="multi-select-chip__actions multi-select-chip__actions--secondary">
                      {resolvedCustomActions.map((action) => (
                        <button
                          type="button"
                          key={action.label}
                          className="multi-select-chip__action-button multi-select-chip__action-button--link"
                          onClick={action.onClick}
                          disabled={Boolean(action.disabled)}
                          aria-label={action.ariaLabel ?? action.label}
                        >
                          {action.label}
                        </button>
                      ))}
                    </div>
                  ) : null}
                  <div className="multi-select-chip__empty">{emptyOptionsLabel}</div>
                </>
              )}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
