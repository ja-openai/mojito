import './chip-dropdown.css';
import './pill.css';

import {
  type CSSProperties,
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
import { createPortal } from 'react-dom';

import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

type PillDropdownOption<T extends string | number> = {
  value: T;
  label: ReactNode;
};

type Props<T extends string | number> = {
  value: T;
  options: Array<PillDropdownOption<T>>;
  onChange: (value: T) => void;
  disabled?: boolean;
  ariaLabel?: string;
  isOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  className?: string;
  panelClassName?: string;
  align?: 'left' | 'right';
};

export function PillDropdown<T extends string | number>({
  value,
  options,
  onChange,
  disabled,
  ariaLabel,
  isOpen,
  onOpenChange,
  className,
  panelClassName,
  align = 'left',
}: Props<T>) {
  const [internalOpen, setInternalOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();
  const controlled = typeof isOpen === 'boolean';
  const resolvedOpen = controlled ? isOpen : internalOpen;

  const updatePanelPosition = useCallback(() => {
    if (!buttonRef.current) {
      return;
    }

    const rect = buttonRef.current.getBoundingClientRect();
    const viewportPadding = 16;
    const maxWidth = Math.min(288, window.innerWidth - viewportPadding * 2);

    setPanelStyle(
      getAnchoredDropdownPanelStyle({
        rect,
        align,
        viewportPadding,
        maxWidth,
      }),
    );
  }, [align]);

  const setOpen = useCallback(
    (next: boolean) => {
      if (!controlled) {
        setInternalOpen(next);
      }
      onOpenChange?.(next);
    },
    [controlled, onOpenChange],
  );

  useEffect(() => {
    if (!resolvedOpen) {
      return;
    }
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!containerRef.current?.contains(target) && !panelRef.current?.contains(target)) {
        setOpen(false);
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
  }, [resolvedOpen, setOpen, updatePanelPosition]);

  useEffect(() => {
    if (disabled && resolvedOpen) {
      setOpen(false);
    }
  }, [disabled, resolvedOpen, setOpen]);

  const selected = options.find((option) => option.value === value);
  const label = selected ? selected.label : String(value);

  const resolvedClassName = ['pill-dropdown', className].filter(Boolean).join(' ');
  const resolvedPanelClassName = ['chip-dropdown__panel', 'pill-dropdown__panel', panelClassName]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={resolvedClassName}
      ref={containerRef}
      onClick={(event) => event.stopPropagation()}
    >
      <button
        type="button"
        className="pill-dropdown__button"
        onClick={() => {
          if (!resolvedOpen) {
            updatePanelPosition();
          }
          setOpen(!resolvedOpen);
        }}
        aria-expanded={resolvedOpen}
        aria-label={ariaLabel}
        disabled={disabled}
        ref={buttonRef}
      >
        <span className="pill-dropdown__label">{label}</span>
        <span className="pill-dropdown__chevron" aria-hidden="true" />
      </button>
      {resolvedOpen
        ? createPortal(
            <div className={resolvedPanelClassName} role="menu" ref={panelRef} style={panelStyle}>
              <div className="pill-dropdown__list">
                {options.map((option) => (
                  <button
                    type="button"
                    key={String(option.value)}
                    className={`pill-dropdown__option${option.value === value ? ' is-active' : ''}`}
                    onClick={() => {
                      setOpen(false);
                      onChange(option.value);
                    }}
                  >
                    <span>{option.label}</span>
                  </button>
                ))}
              </div>
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
