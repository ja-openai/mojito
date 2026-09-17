import { useEffect, useId, useRef, useState } from 'react';

import { isMacPlatform } from '../../utils/keyboardShortcuts';

export function ContentPreviewHelp({ source }: { source: boolean }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const containerRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const dismiss = (event: PointerEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    window.addEventListener('pointerdown', dismiss, true);
    return () => window.removeEventListener('pointerdown', dismiss, true);
  }, [open]);

  return (
    <div
      className="content-preview-help"
      ref={containerRef}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
      onKeyDown={(event) => {
        if (open && event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          setOpen(false);
          buttonRef.current?.focus();
        }
      }}
    >
      <button
        type="button"
        className="content-preview-help__button"
        ref={buttonRef}
        aria-label="Preview help"
        title="Preview help"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen(!open)}
      >
        <span aria-hidden="true">?</span>
      </button>
      {open ? (
        <div
          id={id}
          className="content-preview-help__panel"
          role="region"
          aria-label="Preview help"
        >
          <strong>Preview shortcuts</strong>
          {source ? <p>Edit source content in Git.</p> : null}
          <dl>
            {!source ? (
              <div>
                <dt>Edit passage</dt>
                <dd>Click</dd>
              </div>
            ) : null}
            <div>
              <dt>Follow link</dt>
              <dd>{source ? 'Click' : `${isMacPlatform() ? '⌘' : 'Ctrl'} + click`}</dd>
            </div>
          </dl>
        </div>
      ) : null}
    </div>
  );
}
