import './content-passage-editor.css';

import {
  type CSSProperties,
  type ReactNode,
  type RefObject,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';

type Props = {
  anchor: HTMLElement;
  docked: boolean;
  footerRef?: RefObject<HTMLElement>;
  onAnchorUnavailable: () => void;
  children: (ready: boolean) => ReactNode;
};

/** Keep one editor mounted as it switches between an anchored popover and its pane. */
export function ContentPassageEditor({
  anchor,
  docked,
  footerRef,
  onAnchorUnavailable,
  children,
}: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<CSSProperties>({ visibility: 'hidden' });

  useLayoutEffect(() => {
    const element = panel.current;
    if (!element) return;
    const update = () => {
      if (
        !anchor.isConnected ||
        anchor.closest('[hidden]') ||
        anchor.dataset.contentEditable !== 'true'
      ) {
        onAnchorUnavailable();
        return;
      }
      if (docked) return;
      const viewport = window.visualViewport;
      const leftEdge = (viewport?.offsetLeft ?? 0) + 12;
      const topEdge = (viewport?.offsetTop ?? 0) + 12;
      const width = Math.max(0, (viewport?.width ?? window.innerWidth) - 24);
      const viewportBottom =
        (viewport?.offsetTop ?? 0) + (viewport?.height ?? window.innerHeight) - 12;
      const footer = footerRef?.current?.getBoundingClientRect();
      const bottom = footer?.height ? Math.min(viewportBottom, footer.top - 12) : viewportBottom;
      const height = Math.max(0, bottom - topEdge);
      const editorWidth = Math.min(800, width);
      const bounds = anchor.getBoundingClientRect();
      const editorHeight = Math.min(element.getBoundingClientRect().height, height);
      const below = bounds.bottom + 8;
      const above = bounds.top - editorHeight - 8;
      const preferredTop =
        below + editorHeight <= topEdge + height
          ? Math.max(topEdge, below)
          : above >= topEdge
            ? above
            : Math.max(topEdge, topEdge + height - editorHeight);
      const next: CSSProperties = {
        left: Math.max(leftEdge, Math.min(bounds.left, leftEdge + width - editorWidth)),
        top: Math.max(topEdge, Math.min(preferredTop, bottom - editorHeight)),
        width: editorWidth,
        maxHeight: height,
        visibility: 'visible',
      };
      setPosition((previous) =>
        previous.left === next.left &&
        previous.top === next.top &&
        previous.width === next.width &&
        previous.maxHeight === next.maxHeight &&
        previous.visibility === next.visibility
          ? previous
          : next,
      );
    };
    update();
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(update) : null;
    observer?.observe(element);
    observer?.observe(anchor);
    if (footerRef?.current) observer?.observe(footerRef.current);
    const reader = anchor.closest('.review-project-document__reader');
    const mutations = new MutationObserver(update);
    if (reader)
      mutations.observe(reader, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['hidden', 'data-content-editable'],
      });
    window.addEventListener('resize', update);
    document.addEventListener('scroll', update, true);
    window.visualViewport?.addEventListener('resize', update);
    window.visualViewport?.addEventListener('scroll', update);
    return () => {
      observer?.disconnect();
      mutations.disconnect();
      window.removeEventListener('resize', update);
      document.removeEventListener('scroll', update, true);
      window.visualViewport?.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('scroll', update);
    };
  }, [anchor, docked, footerRef, onAnchorUnavailable]);

  return (
    <div
      ref={panel}
      className={`content-passage-editor${docked ? ' content-passage-editor--docked' : ''}`}
      style={docked ? undefined : position}
      role={docked ? undefined : 'dialog'}
      aria-label={docked ? undefined : 'Translation editor'}
      aria-modal={docked ? undefined : false}
    >
      {children(docked || position.visibility === 'visible')}
    </div>
  );
}
