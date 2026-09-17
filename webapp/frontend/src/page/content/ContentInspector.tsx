import './content-inspector.css';

import {
  type ReactNode,
  type RefObject,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';

const DRAWER_QUERY = '(max-width: 1100px)';
const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex], [contenteditable="true"]';

type Props = {
  children: ReactNode;
  className?: string;
  ariaLabel: string;
  onClose: () => void;
  closeDisabled?: boolean;
  enabled?: boolean;
  panelRef?: RefObject<HTMLDivElement | null>;
};

function drawerViewport() {
  if (typeof window === 'undefined') return false;
  return typeof window.matchMedia === 'function'
    ? window.matchMedia(DRAWER_QUERY).matches
    : window.innerWidth <= 1100;
}

function visibleWithinPanel(element: HTMLElement, panel: HTMLElement) {
  for (let current: HTMLElement | null = element; current; current = current.parentElement) {
    const style = getComputedStyle(current);
    if (style.display === 'none' || style.visibility === 'hidden') return false;
    if (current === panel) break;
  }
  const closedDetails = element.closest('details:not([open])');
  return !closedDetails || closedDetails.querySelector('summary')?.contains(element);
}

function focusableElements(panel: HTMLElement) {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (element) =>
      element.tabIndex >= 0 &&
      !element.matches(':disabled') &&
      !element.closest('[hidden], [inert], [aria-hidden="true"]') &&
      visibleWithinPanel(element, panel),
  );
}

/** Keep the same editor mounted when a pane becomes a drawer; its owner retains closed drafts. */
export function ContentInspector({
  children,
  className,
  ariaLabel,
  onClose,
  closeDisabled = false,
  enabled = true,
  panelRef,
}: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const portalFocus = useRef<HTMLElement | null>(null);
  const returnFocus = useRef<HTMLElement | null>(
    typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null,
  );
  const wasDrawer = useRef(false);
  const [narrow, setNarrow] = useState(drawerViewport);
  const drawer = enabled && narrow;
  useImperativeHandle(panelRef, () => panel.current!, []);

  useEffect(() => {
    const media = typeof window.matchMedia === 'function' ? window.matchMedia(DRAWER_QUERY) : null;
    const update = () => setNarrow(drawerViewport());
    update();
    if (typeof media?.addEventListener === 'function') {
      media.addEventListener('change', update);
      return () => media.removeEventListener?.('change', update);
    }
    if (typeof media?.addListener === 'function') {
      media.addListener(update);
      return () => media.removeListener?.(update);
    }
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, []);

  useEffect(() => {
    const node = panel.current;
    return () => {
      const origin = returnFocus.current;
      if (
        wasDrawer.current &&
        origin?.isConnected &&
        (document.activeElement === document.body || node?.contains(document.activeElement))
      ) {
        origin.focus({ preventScroll: true });
      }
    };
  }, []);

  useEffect(() => {
    const node = panel.current;
    if (!drawer || !node) return;
    wasDrawer.current = true;
    if (
      document.activeElement instanceof HTMLElement &&
      document.activeElement !== document.body &&
      !node.contains(document.activeElement)
    ) {
      returnFocus.current = document.activeElement;
    }
    const initial = () => focusableElements(node)[0] ?? node;
    if (!node.contains(document.activeElement)) initial().focus({ preventScroll: true });
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const otherModal = (element: Element | null) => {
      const modal = element?.closest('[aria-modal="true"]');
      return modal && modal !== node;
    };
    const onFocus = (event: FocusEvent) => {
      const target = event.target instanceof Element ? event.target : null;
      if (target && !node.contains(target) && target !== portalFocus.current && !otherModal(target))
        initial().focus({ preventScroll: true });
    };
    const onKeyDown = (event: KeyboardEvent) => {
      // Existing editors own Escape, composition handling and save shortcuts.
      if (
        event.key !== 'Tab' ||
        otherModal(document.activeElement) ||
        document.activeElement === portalFocus.current
      )
        return;
      const elements = focusableElements(node);
      const first = elements[0] ?? node;
      const last = elements[elements.length - 1] ?? node;
      if (
        event.shiftKey &&
        (document.activeElement === first || !node.contains(document.activeElement))
      ) {
        event.preventDefault();
        last.focus();
      } else if (
        !event.shiftKey &&
        (document.activeElement === last || !node.contains(document.activeElement))
      ) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('focusin', onFocus);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('focusin', onFocus);
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [drawer]);

  const close = () => {
    if (!closeDisabled) onClose();
  };

  return (
    <>
      {drawer ? (
        <button
          type="button"
          className="content-inspector__backdrop"
          aria-hidden="true"
          tabIndex={-1}
          disabled={closeDisabled}
          onClick={close}
        />
      ) : null}
      <div
        ref={panel}
        className={['content-inspector', className, drawer && 'content-inspector--drawer']
          .filter(Boolean)
          .join(' ')}
        role={drawer ? 'dialog' : 'complementary'}
        aria-modal={drawer ? true : undefined}
        aria-label={ariaLabel}
        tabIndex={drawer ? -1 : undefined}
        onFocusCapture={(event) => {
          // React focus events include this editor's portals, unlike DOM containment.
          // Let their controls keep focus without admitting unrelated page controls.
          portalFocus.current = event.currentTarget.contains(event.target) ? null : event.target;
        }}
      >
        {drawer ? (
          <header className="content-inspector__header">
            <button type="button" disabled={closeDisabled} onClick={close}>
              <span aria-hidden="true">←</span> Back to preview
            </button>
            <span>{ariaLabel}</span>
          </header>
        ) : null}
        {children}
      </div>
    </>
  );
}
