import type { CSSProperties } from 'react';

type Align = 'left' | 'right';

type AnchoredDropdownPanelStyleOptions = {
  rect: DOMRect;
  align: Align;
  maxWidth: number;
  panelHeight?: number;
  viewportPadding?: number;
  gap?: number;
};

export function getAnchoredDropdownPanelStyle({
  rect,
  align,
  maxWidth,
  panelHeight = 0,
  viewportPadding = 16,
  gap = 8,
}: AnchoredDropdownPanelStyleOptions): CSSProperties {
  const spaceBelow = window.innerHeight - viewportPadding - rect.bottom - gap;
  const spaceAbove = rect.top - viewportPadding - gap;
  const shouldOpenAbove = panelHeight > spaceBelow && spaceAbove > spaceBelow;

  return {
    position: 'fixed',
    top: shouldOpenAbove
      ? Math.max(viewportPadding, rect.top - panelHeight - gap)
      : Math.min(rect.bottom + gap, window.innerHeight - viewportPadding),
    left:
      align === 'right'
        ? 'auto'
        : Math.max(
            viewportPadding,
            Math.min(rect.left, window.innerWidth - maxWidth - viewportPadding),
          ),
    right: align === 'right' ? Math.max(viewportPadding, window.innerWidth - rect.right) : 'auto',
    minWidth: rect.width,
    maxWidth,
    zIndex: 1200,
  };
}
