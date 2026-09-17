import './resizable-master-detail-layout.css';

import {
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';

type ResizableMasterDetailLayoutProps = {
  sidebar: ReactNode;
  detail: ReactNode;
  storageKey: string;
  sidebarLabel: string;
  detailLabel: string;
  resizeLabel: string;
  className?: string;
  sidebarClassName?: string;
  detailClassName?: string;
  defaultSidebarWidthPercent?: number;
  minSidebarWidthPercent?: number;
  maxSidebarWidthPercent?: number;
  detailVisible?: boolean;
  collapsible?: boolean;
};

const DEFAULT_SIDEBAR_WIDTH_PERCENT = 34;
const MIN_SIDEBAR_WIDTH_PERCENT = 24;
const MAX_SIDEBAR_WIDTH_PERCENT = 48;

export function ResizableMasterDetailLayout({
  sidebar,
  detail,
  storageKey,
  sidebarLabel,
  detailLabel,
  resizeLabel,
  className,
  sidebarClassName,
  detailClassName,
  defaultSidebarWidthPercent = DEFAULT_SIDEBAR_WIDTH_PERCENT,
  minSidebarWidthPercent = MIN_SIDEBAR_WIDTH_PERCENT,
  maxSidebarWidthPercent = MAX_SIDEBAR_WIDTH_PERCENT,
  detailVisible = true,
  collapsible = false,
}: ResizableMasterDetailLayoutProps) {
  const layoutRef = useRef<HTMLDivElement | null>(null);
  const [widths, setWidths] = useState<Record<string, number>>({});
  const [collapsedPanels, setCollapsedPanels] = useState<Record<string, boolean>>({});
  const sidebarWidthPercent = clampNumber(
    widths[storageKey] ?? readStoredNumber(storageKey, defaultSidebarWidthPercent),
    minSidebarWidthPercent,
    maxSidebarWidthPercent,
  );
  const collapsed = collapsible && detailVisible && Boolean(collapsedPanels[storageKey]);
  const [isResizing, setIsResizing] = useState(false);
  const resizeCleanup = useRef<(() => void) | null>(null);
  useEffect(() => () => resizeCleanup.current?.(), []);
  useEffect(() => {
    resizeCleanup.current?.();
    setIsResizing(false);
  }, [detailVisible, storageKey]);

  const setCollapsed = useCallback(
    (value: boolean) => setCollapsedPanels((previous) => ({ ...previous, [storageKey]: value })),
    [storageKey],
  );

  const setSidebarWidthPercent = useCallback(
    (nextValue: number) => {
      const clampedValue = clampNumber(nextValue, minSidebarWidthPercent, maxSidebarWidthPercent);
      setWidths((previous) => ({ ...previous, [storageKey]: clampedValue }));
      try {
        window.localStorage.setItem(storageKey, String(clampedValue));
      } catch {
        // Keep resizing usable even when localStorage is unavailable.
      }
    },
    [maxSidebarWidthPercent, minSidebarWidthPercent, storageKey],
  );

  const handlePointerDown = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return;
      event.preventDefault();
      resizeCleanup.current?.();
      const layoutElement = layoutRef.current;
      if (!layoutElement) {
        return;
      }
      setIsResizing(true);

      const resizeFromClientX = (clientX: number) => {
        const rect = layoutElement.getBoundingClientRect();
        if (!rect.width) {
          return;
        }
        const percent = ((clientX - rect.left) / rect.width) * 100;
        if (collapsible && percent <= 8) {
          setCollapsed(true);
        } else {
          setCollapsed(false);
          setSidebarWidthPercent(percent);
        }
      };

      resizeFromClientX(event.clientX);

      const handlePointerMove = (moveEvent: PointerEvent) => {
        resizeFromClientX(moveEvent.clientX);
      };
      const cleanup = () => {
        window.removeEventListener('pointermove', handlePointerMove);
        window.removeEventListener('pointerup', stopResize);
        window.removeEventListener('pointercancel', stopResize);
        resizeCleanup.current = null;
      };
      const stopResize = () => {
        setIsResizing(false);
        cleanup();
      };
      resizeCleanup.current = cleanup;

      window.addEventListener('pointermove', handlePointerMove);
      window.addEventListener('pointerup', stopResize);
      window.addEventListener('pointercancel', stopResize);
    },
    [collapsible, setCollapsed, setSidebarWidthPercent],
  );

  const handleKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLDivElement>) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      if (collapsed) {
        if (event.key === 'ArrowRight') setCollapsed(false);
        return;
      }
      setSidebarWidthPercent(sidebarWidthPercent + (event.key === 'ArrowRight' ? 2 : -2));
    },
    [collapsed, setCollapsed, setSidebarWidthPercent, sidebarWidthPercent],
  );

  const style = {
    '--resizable-master-detail-sidebar-width': `${sidebarWidthPercent}%`,
  } as CSSProperties;

  return (
    <div
      ref={layoutRef}
      className={[
        'resizable-master-detail-layout',
        isResizing ? 'is-resizing' : '',
        collapsed ? 'resizable-master-detail-layout--collapsed' : '',
        !detailVisible ? 'resizable-master-detail-layout--detail-hidden' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      style={style}
    >
      <aside
        className={['resizable-master-detail-layout__sidebar', sidebarClassName]
          .filter(Boolean)
          .join(' ')}
        aria-label={sidebarLabel}
        hidden={collapsed}
      >
        {sidebar}
      </aside>

      <div
        className={`resizable-master-detail-layout__resize-handle${
          isResizing ? ' is-resizing' : ''
        }`}
        hidden={!detailVisible}
      >
        <div
          className="resizable-master-detail-layout__separator"
          role="separator"
          aria-label={resizeLabel}
          aria-orientation="vertical"
          aria-valuemin={collapsible ? 0 : minSidebarWidthPercent}
          aria-valuemax={maxSidebarWidthPercent}
          aria-valuenow={collapsed ? 0 : Math.round(sidebarWidthPercent)}
          tabIndex={0}
          onPointerDown={handlePointerDown}
          onKeyDown={handleKeyDown}
        />
        {collapsible ? (
          <button
            type="button"
            className="resizable-master-detail-layout__handle-grip"
            aria-label={`${collapsed ? 'Expand' : 'Collapse'} ${sidebarLabel.toLowerCase()}`}
            title={`${collapsed ? 'Expand' : 'Collapse'} ${sidebarLabel.toLowerCase()}`}
            aria-expanded={!collapsed}
            onPointerDown={(event) => event.stopPropagation()}
            onClick={() => setCollapsed(!collapsed)}
          >
            <svg viewBox="0 0 8 12" width="8" height="12" aria-hidden="true">
              <path
                d={collapsed ? 'M2 2l4 4-4 4' : 'M6 2L2 6l4 4'}
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
              />
            </svg>
          </button>
        ) : (
          <span className="resizable-master-detail-layout__handle-grip" aria-hidden="true" />
        )}
      </div>

      <section
        className={['resizable-master-detail-layout__detail', detailClassName]
          .filter(Boolean)
          .join(' ')}
        aria-label={detailLabel}
        hidden={!detailVisible}
      >
        {detail}
      </section>
    </div>
  );
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function readStoredNumber(storageKey: string, fallbackValue: number): number {
  try {
    const storedValue = window.localStorage.getItem(storageKey);
    if (!storedValue) {
      return fallbackValue;
    }
    const parsedValue = Number(storedValue);
    return Number.isFinite(parsedValue) ? parsedValue : fallbackValue;
  } catch {
    return fallbackValue;
  }
}
