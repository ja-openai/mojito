import type { CSSProperties } from 'react';

type Align = 'left' | 'right';

type AnchoredDropdownPanelStyleOptions = {
  rect: DOMRect;
  align: Align;
  maxWidth: number;
  viewportPadding?: number;
  gap?: number;
};

export function getAnchoredDropdownPanelStyle({
  rect,
  align,
  maxWidth,
  viewportPadding = 16,
  gap = 8,
}: AnchoredDropdownPanelStyleOptions): CSSProperties {
  return {
    position: 'fixed',
    top: Math.min(rect.bottom + gap, window.innerHeight - viewportPadding),
    left:
      align === 'right'
        ? 'auto'
        : Math.max(
            viewportPadding,
            Math.min(rect.left, window.innerWidth - maxWidth - viewportPadding),
          ),
    right:
      align === 'right' ? Math.max(viewportPadding, window.innerWidth - rect.right) : 'auto',
    minWidth: rect.width,
    maxWidth,
    zIndex: 1000,
  };
}
