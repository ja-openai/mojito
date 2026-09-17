import './FileTree.css';

import { type ReactNode, useEffect, useRef } from 'react';

import { useVirtualRows } from './virtual/useVirtualRows';

export type FileTreeItem = {
  key: string;
  depth: number;
  content: ReactNode;
  selected?: boolean;
  focusable?: boolean;
};

/** Data loading and expansion belong to the caller; only visible rows are mounted here. */
export function FileTree({
  items,
  ariaLabel,
  revealKey,
}: {
  items: FileTreeItem[];
  ariaLabel: string;
  revealKey?: string | null;
}) {
  const pendingFocus = useRef<string | null>(null);
  const revealedKey = useRef<string | null>(null);
  const rows = useVirtualRows<HTMLDivElement>({
    count: items.length,
    estimateSize: () => 32,
    getItemKey: (index) => items[index].key,
  });
  const revealIndex = items.findIndex((item) => item.key === revealKey);
  const viewportHeight = rows.virtualizer.scrollRect?.height ?? 0;
  const { scrollToIndex } = rows;
  useEffect(() => {
    if (!viewportHeight) {
      revealedKey.current = null;
    } else if (revealKey && revealIndex >= 0 && revealedKey.current !== revealKey) {
      revealedKey.current = revealKey;
      scrollToIndex(revealIndex, { align: 'auto' });
    }
  }, [revealKey, revealIndex, viewportHeight, scrollToIndex]);

  return (
    <div
      className="file-tree"
      role="list"
      aria-label={ariaLabel}
      ref={rows.scrollRef}
      onKeyDown={(event) => {
        if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
        const target = event.target as HTMLElement;
        const row = target.closest<HTMLElement>('[data-index]');
        if (!row || !target.closest('button')) return;
        const index = Number(row.dataset.index);
        const toggle = row.querySelector<HTMLButtonElement>('.file-tree__toggle');
        if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
          if (toggle?.getAttribute('aria-expanded') === String(event.key === 'ArrowLeft')) {
            event.preventDefault();
            event.stopPropagation();
            toggle.click();
          }
          return;
        }
        let next =
          event.key === 'ArrowDown'
            ? Math.min(items.length - 1, index + 1)
            : event.key === 'ArrowUp'
              ? Math.max(0, index - 1)
              : event.key === 'Home'
                ? 0
                : event.key === 'End'
                  ? items.length - 1
                  : null;
        if (next == null) return;
        event.preventDefault();
        event.stopPropagation();
        const direction = event.key === 'ArrowUp' || event.key === 'End' ? -1 : 1;
        while (next >= 0 && next < items.length && items[next].focusable === false) {
          next += direction;
        }
        if (next < 0 || next >= items.length) return;
        pendingFocus.current = items[next].key;
        scrollToIndex(next, { align: 'auto' });
        const mounted = rows.scrollRef.current?.querySelector<HTMLElement>(
          `[data-index="${next}"]`,
        );
        if (mounted) {
          (
            mounted.querySelector<HTMLElement>('.file-tree__label:not(:disabled)') ??
            mounted.querySelector<HTMLElement>('button:not(:disabled)')
          )?.focus({ preventScroll: true });
          pendingFocus.current = null;
        }
      }}
    >
      <div className="file-tree__canvas" style={{ height: rows.totalSize }}>
        {rows.items.map((virtualRow) => {
          const item = items[virtualRow.index];
          return (
            <div
              key={item.key}
              role="listitem"
              aria-posinset={virtualRow.index + 1}
              aria-setsize={items.length}
              className="file-tree__item"
              data-index={virtualRow.index}
              style={{
                transform: `translateY(${virtualRow.start}px)`,
                paddingInlineStart: `${item.depth}rem`,
              }}
              ref={(element) => {
                rows.measureElement(element);
                if (element && pendingFocus.current === item.key) {
                  (
                    element.querySelector<HTMLElement>('.file-tree__label:not(:disabled)') ??
                    element.querySelector<HTMLElement>('button:not(:disabled)')
                  )?.focus({ preventScroll: true });
                  pendingFocus.current = null;
                }
              }}
            >
              {item.content}
            </div>
          );
        })}
      </div>
    </div>
  );
}

type FileTreeRowProps = {
  label: ReactNode;
  title?: string;
  ariaLabel: string;
  selected?: boolean;
  current?: 'page' | 'location';
  disabled?: boolean;
  expanded?: boolean;
  onToggle?: () => void;
  toggleLabel?: string;
  onSelect: () => void;
};

export function FileTreeRow({
  label,
  title,
  ariaLabel,
  selected,
  current = 'location',
  disabled,
  expanded,
  onToggle,
  toggleLabel,
  onSelect,
}: FileTreeRowProps) {
  const folder = expanded !== undefined;
  return (
    <div
      className={`file-tree__row${folder ? '' : ' file-tree__row--file'}${selected ? ' file-tree__row--selected' : ''}`}
    >
      {folder ? (
        <button
          type="button"
          className="file-tree__toggle"
          aria-label={toggleLabel ?? `${expanded ? 'Collapse' : 'Expand'} ${title ?? 'folder'}`}
          aria-expanded={expanded}
          disabled={disabled}
          onClick={onToggle}
        >
          <span aria-hidden="true">{expanded ? '▾' : '▸'}</span>
        </button>
      ) : null}
      <button
        type="button"
        className="file-tree__label"
        aria-label={ariaLabel}
        aria-current={selected ? current : undefined}
        title={title}
        disabled={disabled}
        onClick={onSelect}
      >
        {label}
      </button>
    </div>
  );
}
