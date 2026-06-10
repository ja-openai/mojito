import './resizable-master-detail-layout.css';

import {
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
  useCallback,
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
}: ResizableMasterDetailLayoutProps) {
  const layoutRef = useRef<HTMLDivElement | null>(null);
  const [sidebarWidthPercent, setSidebarWidthPercentState] = useState(() =>
    clampNumber(
      readStoredNumber(storageKey, defaultSidebarWidthPercent),
      minSidebarWidthPercent,
      maxSidebarWidthPercent,
    ),
  );
  const [isResizing, setIsResizing] = useState(false);

  const setSidebarWidthPercent = useCallback(
    (nextValue: number) => {
      const clampedValue = clampNumber(nextValue, minSidebarWidthPercent, maxSidebarWidthPercent);
      setSidebarWidthPercentState(clampedValue);
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
      event.preventDefault();
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
        setSidebarWidthPercent(((clientX - rect.left) / rect.width) * 100);
      };

      resizeFromClientX(event.clientX);

      const handlePointerMove = (moveEvent: PointerEvent) => {
        resizeFromClientX(moveEvent.clientX);
      };
      const stopResize = () => {
        setIsResizing(false);
        window.removeEventListener('pointermove', handlePointerMove);
        window.removeEventListener('pointerup', stopResize);
        window.removeEventListener('pointercancel', stopResize);
      };

      window.addEventListener('pointermove', handlePointerMove);
      window.addEventListener('pointerup', stopResize);
      window.addEventListener('pointercancel', stopResize);
    },
    [setSidebarWidthPercent],
  );

  const handleKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLDivElement>) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
        return;
      }
      event.preventDefault();
      setSidebarWidthPercent(sidebarWidthPercent + (event.key === 'ArrowRight' ? 2 : -2));
    },
    [setSidebarWidthPercent, sidebarWidthPercent],
  );

  const style = {
    '--resizable-master-detail-sidebar-width': `${sidebarWidthPercent}%`,
  } as CSSProperties;

  return (
    <div
      ref={layoutRef}
      className={['resizable-master-detail-layout', isResizing ? 'is-resizing' : '', className]
        .filter(Boolean)
        .join(' ')}
      style={style}
    >
      <aside
        className={['resizable-master-detail-layout__sidebar', sidebarClassName]
          .filter(Boolean)
          .join(' ')}
        aria-label={sidebarLabel}
      >
        {sidebar}
      </aside>

      <div
        className={`resizable-master-detail-layout__resize-handle${
          isResizing ? ' is-resizing' : ''
        }`}
        role="separator"
        aria-label={resizeLabel}
        aria-orientation="vertical"
        aria-valuemin={minSidebarWidthPercent}
        aria-valuemax={maxSidebarWidthPercent}
        aria-valuenow={Math.round(sidebarWidthPercent)}
        tabIndex={0}
        onPointerDown={handlePointerDown}
        onKeyDown={handleKeyDown}
      >
        <span className="resizable-master-detail-layout__handle-grip" aria-hidden="true" />
      </div>

      <section
        className={['resizable-master-detail-layout__detail', detailClassName]
          .filter(Boolean)
          .join(' ')}
        aria-label={detailLabel}
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
