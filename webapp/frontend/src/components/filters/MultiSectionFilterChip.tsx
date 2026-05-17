import { type CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { resultSizePresets, WORKSET_SIZE_MIN } from '../../page/workbench/workbench-constants';
import { getAnchoredDropdownPanelStyle } from '../dropdownPosition';

export type FilterOption<T extends string | number> = { value: T; label: string; helper?: string };

export type RadioSection<T extends string | number = string> = {
  kind: 'radio';
  label: string;
  options: Array<FilterOption<T>>;
  value: T;
  onChange: (value: T) => void;
};

export type SizeSection = {
  kind: 'size';
  label: string;
  options?: Array<FilterOption<number>>;
  value: number;
  onChange: (value: number) => void;
  min?: number;
};

export type DateQuickRange = {
  label: string;
  after?: string | null;
  before?: string | null;
};

export type DateSection = {
  kind: 'date';
  label: string;
  after?: string | null;
  before?: string | null;
  onChangeAfter?: (value: string | null) => void;
  onChangeBefore?: (value: string | null) => void;
  quickRanges?: DateQuickRange[];
  clearLabel?: string;
  onClear?: () => void;
  afterLabel?: string;
  beforeLabel?: string;
};

export type FilterSection = RadioSection<string | number> | SizeSection | DateSection;

type ResolvedClassNames = {
  button: string;
  panel: string;
  section: string;
  label: string;
  list: string;
  option: string;
  helper: string;
  pills: string;
  quick: string;
  quickChip: string;
  custom: string;
  customLabel: string;
  customInput: string;
  dateInput: string;
  clear: string;
};

export type MultiSectionFilterChipClassNames = Partial<ResolvedClassNames>;

type Props = {
  sections: Array<FilterSection>;
  align?: 'left' | 'right';
  summary?: string;
  ariaLabel?: string;
  className?: string;
  classNames?: MultiSectionFilterChipClassNames;
  disabled?: boolean;
  closeOnSelection?: boolean;
};

const defaultClassNames: ResolvedClassNames = {
  button: 'workbench-filterchip__button',
  panel: 'workbench-filterchip__panel',
  section: 'workbench-searchmode__section',
  label: 'workbench-searchmode__label',
  list: 'workbench-searchmode__list',
  option: 'workbench-searchmode__option',
  helper: 'workbench-searchmode__helper',
  pills: 'workbench-filterchip__pills',
  quick: 'workbench-datefilter__quick',
  quickChip: 'workbench-datefilter__quick-chip',
  custom: 'workbench-worksetcustom',
  customLabel: 'workbench-worksetcustom__label',
  customInput: 'workbench-worksetcustom__input',
  dateInput: 'workbench-datefilter__input',
  clear: 'workbench-filterchip__clear-link',
};

export function MultiSectionFilterChip({
  sections,
  align = 'right',
  summary,
  ariaLabel,
  className,
  classNames,
  disabled = false,
  closeOnSelection = false,
}: Props) {
  const mergedClassNames = useMemo<ResolvedClassNames>(
    () => ({ ...defaultClassNames, ...classNames }),
    [classNames],
  );
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const sizeCommitHandlersRef = useRef<Set<() => void>>(new Set());
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();

  const commitSizeSections = useCallback(() => {
    sizeCommitHandlersRef.current.forEach((commit) => commit());
  }, []);
  const registerSizeCommitHandler = useCallback((commit: () => void) => {
    sizeCommitHandlersRef.current.add(commit);
    return () => {
      sizeCommitHandlersRef.current.delete(commit);
    };
  }, []);

  const updatePanelPosition = useCallback(() => {
    if (!buttonRef.current) {
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    const viewportPadding = 16;
    const gap = 8;
    const maxWidth = Math.min(460, window.innerWidth - viewportPadding * 2);

    setPanelStyle(
      getAnchoredDropdownPanelStyle({
        rect,
        align,
        viewportPadding,
        gap,
        maxWidth,
      }),
    );
  }, [align]);

  useEffect(() => {
    if (disabled && isOpen) {
      commitSizeSections();
      setIsOpen(false);
    }
  }, [commitSizeSections, disabled, isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!containerRef.current?.contains(target) && !panelRef.current?.contains(target)) {
        commitSizeSections();
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
  }, [commitSizeSections, isOpen, updatePanelPosition]);

  const computedSummary = useMemo(() => {
    if (summary) return summary;
    const parts: string[] = [];
    sections.forEach((section) => {
      if (section.kind === 'radio') {
        const selected = section.options.find((opt) => opt.value === section.value);
        if (selected) parts.push(selected.label);
      } else if (section.kind === 'size') {
        const sizeOptions =
          section.options && section.options.length > 0 ? section.options : resultSizePresets;
        const presetLabel = sizeOptions.find((opt) => opt.value === section.value)?.label;
        const label =
          presetLabel ??
          (section.value >= 1000 && section.value % 1000 === 0
            ? `${section.value / 1000}k`
            : String(section.value));
        parts.push(`Size ${label}`);
      } else if (section.kind === 'date') {
        if (section.after || section.before) {
          parts.push(section.label);
        }
      }
    });
    return parts.join(' · ') || 'Filters';
  }, [sections, summary]);

  const containerClassName = ['chip-dropdown', className].filter(Boolean).join(' ');
  const buttonClassName = ['chip-dropdown__button', mergedClassNames.button]
    .filter(Boolean)
    .join(' ');
  const panelClassName = ['chip-dropdown__panel', mergedClassNames.panel].filter(Boolean).join(' ');

  return (
    <div className={containerClassName} ref={containerRef} data-align={align}>
      <button
        type="button"
        className={buttonClassName}
        onClick={() => {
          if (disabled) return;
          if (isOpen) {
            commitSizeSections();
          }
          setIsOpen((prev) => !prev);
        }}
        aria-expanded={isOpen}
        aria-label={ariaLabel}
        disabled={disabled}
        ref={buttonRef}
      >
        <span className="chip-dropdown__summary">{computedSummary}</span>
        <span className="chip-dropdown__chevron" aria-hidden="true" />
      </button>
      {isOpen
        ? createPortal(
            <div className={panelClassName} role="menu" ref={panelRef} style={panelStyle}>
              {sections.map((section, index) => {
                if (section.kind === 'radio') {
                  return (
                    <div className={mergedClassNames.section} key={`radio-${index}`}>
                      <div className={mergedClassNames.label}>{section.label}</div>
                      <div className={mergedClassNames.list}>
                        {section.options.map((option) => (
                          <button
                            type="button"
                            key={String(option.value)}
                            className={`${mergedClassNames.option}${
                              option.value === section.value ? ' is-active' : ''
                            }`}
                            onClick={() => {
                              section.onChange(option.value);
                              if (closeOnSelection) {
                                setIsOpen(false);
                              }
                            }}
                          >
                            <span>{option.label}</span>
                            {option.helper ? (
                              <span className={mergedClassNames.helper}>{option.helper}</span>
                            ) : null}
                          </button>
                        ))}
                      </div>
                    </div>
                  );
                }
                if (section.kind === 'size') {
                  return (
                    <SizeFilterSection
                      key={`size-${index}`}
                      section={section}
                      classNames={mergedClassNames}
                      closeOnSelection={closeOnSelection}
                      onClose={() => setIsOpen(false)}
                      registerCommitHandler={registerSizeCommitHandler}
                    />
                  );
                }
                if (section.kind === 'date') {
                  const hasQuickRanges = Boolean(section.quickRanges?.length);
                  const showClearButton =
                    Boolean(section.onClear) && Boolean(section.after || section.before);
                  const afterLabel = section.afterLabel ?? `${section.label} after`;
                  const beforeLabel = section.beforeLabel ?? `${section.label} before`;
                  return (
                    <div className={mergedClassNames.section} key={`date-${index}`}>
                      <div className={mergedClassNames.label}>{section.label}</div>
                      <div className={mergedClassNames.list}>
                        {section.onChangeAfter ? (
                          <input
                            type="datetime-local"
                            value={section.after ? isoToLocalInput(section.after) : ''}
                            onChange={(event) =>
                              section.onChangeAfter?.(
                                event.target.value ? localInputToIso(event.target.value) : null,
                              )
                            }
                            className={mergedClassNames.dateInput}
                            aria-label={afterLabel}
                            title={afterLabel}
                          />
                        ) : null}
                        {section.onChangeBefore ? (
                          <input
                            type="datetime-local"
                            value={section.before ? isoToLocalInput(section.before) : ''}
                            onChange={(event) =>
                              section.onChangeBefore?.(
                                event.target.value ? localInputToIso(event.target.value) : null,
                              )
                            }
                            className={mergedClassNames.dateInput}
                            aria-label={beforeLabel}
                            title={beforeLabel}
                          />
                        ) : null}
                      </div>
                      {hasQuickRanges || showClearButton ? (
                        <div className={mergedClassNames.quick}>
                          {section.quickRanges?.map((range) => (
                            <button
                              type="button"
                              key={range.label}
                              className={mergedClassNames.quickChip}
                              onClick={() => {
                                section.onChangeAfter?.(range.after ?? null);
                                section.onChangeBefore?.(range.before ?? null);
                                if (closeOnSelection) {
                                  setIsOpen(false);
                                }
                              }}
                            >
                              {range.label}
                            </button>
                          ))}
                          {showClearButton ? (
                            <button
                              type="button"
                              className={mergedClassNames.clear}
                              onClick={() => {
                                section.onClear?.();
                                if (closeOnSelection) {
                                  setIsOpen(false);
                                }
                              }}
                            >
                              {section.clearLabel ?? 'Clear'}
                            </button>
                          ) : null}
                        </div>
                      ) : null}
                    </div>
                  );
                }
                return null;
              })}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}

function SizeFilterSection({
  section,
  classNames,
  closeOnSelection,
  onClose,
  registerCommitHandler,
}: {
  section: SizeSection;
  classNames: ResolvedClassNames;
  closeOnSelection: boolean;
  onClose: () => void;
  registerCommitHandler: (commit: () => void) => () => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const sizeOptions =
    section.options && section.options.length > 0 ? section.options : resultSizePresets;
  const sizeMin = section.min ?? WORKSET_SIZE_MIN;
  const sizeIsPreset = sizeOptions.some((option) => option.value === section.value);
  const [sizeDraft, setSizeDraft] = useState(() => String(section.value ?? ''));
  const [showCustomSize, setShowCustomSize] = useState(() => !sizeIsPreset);

  const commitSizeDraft = useCallback(() => {
    const parsed = parseInt(sizeDraft, 10);
    if (Number.isNaN(parsed) || parsed < sizeMin) {
      setSizeDraft(String(section.value ?? ''));
      return;
    }
    if (parsed !== section.value) {
      section.onChange(parsed);
    }
  }, [section, sizeDraft, sizeMin]);

  useEffect(() => registerCommitHandler(commitSizeDraft), [commitSizeDraft, registerCommitHandler]);

  useEffect(() => {
    const nextDraft = String(section.value ?? '');
    const nextShowCustomSize = !sizeIsPreset;
    setSizeDraft((prev) => (prev === nextDraft ? prev : nextDraft));
    setShowCustomSize((prev) => (prev === nextShowCustomSize ? prev : nextShowCustomSize));
  }, [section.value, sizeIsPreset]);

  useEffect(() => {
    if (showCustomSize) {
      queueMicrotask(() => {
        inputRef.current?.focus();
        inputRef.current?.select();
      });
    }
  }, [showCustomSize]);

  return (
    <div className={classNames.section}>
      <div className={classNames.label}>{section.label}</div>
      <div className={classNames.pills}>
        {sizeOptions.map((option) => (
          <button
            type="button"
            key={option.value}
            className={`${classNames.quickChip}${
              option.value === section.value ? ' is-active' : ''
            }`}
            onClick={() => {
              section.onChange(option.value);
              setShowCustomSize(false);
              if (closeOnSelection) {
                onClose();
              }
            }}
          >
            {option.label}
          </button>
        ))}
        {showCustomSize ? (
          <div className={`${classNames.custom} is-active`}>
            <span className={classNames.customLabel}>Custom</span>
            <input
              ref={inputRef}
              className={classNames.customInput}
              type="number"
              inputMode="numeric"
              min={sizeMin}
              value={sizeDraft}
              onChange={(event) => setSizeDraft(event.target.value)}
              onBlur={() => {
                commitSizeDraft();
                if (closeOnSelection) {
                  onClose();
                }
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  commitSizeDraft();
                  if (closeOnSelection) {
                    onClose();
                  }
                }
                if (event.key === 'Escape') {
                  setSizeDraft(String(section.value ?? ''));
                  setShowCustomSize(false);
                }
              }}
            />
          </div>
        ) : (
          <button
            type="button"
            className={classNames.quickChip}
            onClick={() => {
              setShowCustomSize(true);
              setSizeDraft(String(section.value ?? ''));
            }}
          >
            Custom…
          </button>
        )}
      </div>
    </div>
  );
}

function isoToLocalInput(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const tzOffset = date.getTimezoneOffset() * 60000;
  const local = new Date(date.getTime() - tzOffset);
  return local.toISOString().slice(0, 16);
}

function localInputToIso(local: string): string {
  if (!local) return '';
  const date = new Date(local);
  return date.toISOString();
}
