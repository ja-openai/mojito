import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ContentPassageEditor } from './ContentPassageEditor';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  document.querySelector('[data-passage-editor-fixture]')?.remove();
});

describe('ContentPassageEditor', () => {
  it('preserves the editor DOM, draft, focus, and selection when docking and returning inline', () => {
    const fixture = document.createElement('div');
    fixture.dataset.passageEditorFixture = 'true';
    const anchor = document.createElement('button');
    anchor.dataset.contentEditable = 'true';
    fixture.append(anchor);
    document.body.append(fixture);
    const onAnchorUnavailable = vi.fn();
    const editor = (docked: boolean) => (
      <ContentPassageEditor
        anchor={anchor}
        docked={docked}
        onAnchorUnavailable={onAnchorUnavailable}
      >
        {() => <textarea aria-label="Translation" defaultValue="An unsaved draft" />}
      </ContentPassageEditor>
    );
    const { rerender, container } = render(editor(false));
    const field = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Translation' });
    const panel = screen.getByRole('dialog', { name: 'Translation editor' });
    expect(container).toContainElement(panel);
    fireEvent.change(field, { target: { value: 'Keep the edited draft' } });
    field.focus();
    field.setSelectionRange(5, 15);

    rerender(editor(true));
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(field);
    expect(field.parentElement).toBe(panel);
    expect(panel).toHaveClass('content-passage-editor--docked');
    expect(panel).not.toHaveAttribute('role');
    expect(panel.style.width).toBe('');
    expect(field).toHaveValue('Keep the edited draft');
    expect(field).toHaveFocus();
    expect([field.selectionStart, field.selectionEnd]).toEqual([5, 15]);

    rerender(editor(false));
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toBe(panel);
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(field);
    expect(panel).not.toHaveClass('content-passage-editor--docked');
    expect(field).toHaveValue('Keep the edited draft');
    expect(field).toHaveFocus();
    expect([field.selectionStart, field.selectionEnd]).toEqual([5, 15]);
    expect(onAnchorUnavailable).not.toHaveBeenCalled();
  });

  it('keeps the editor above the shortcut bar when its passage scrolls below the viewport', () => {
    const fixture = document.createElement('div');
    fixture.dataset.passageEditorFixture = 'true';
    const reader = document.createElement('article');
    reader.className = 'review-project-document__reader';
    const anchor = document.createElement('button');
    anchor.dataset.contentEditable = 'true';
    reader.append(anchor);
    const footer = document.createElement('div');
    fixture.append(reader, footer);
    document.body.append(fixture);

    const footerTop = window.innerHeight - 38;
    const editorHeight = 300;
    let anchorTop = footerTop - 350;
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
      this: HTMLElement,
    ) {
      if (this === anchor) return new DOMRect(80, anchorTop, 600, 24);
      if (this === footer) return new DOMRect(0, footerTop, 1000, 38);
      if (this.classList.contains('content-passage-editor'))
        return new DOMRect(0, 0, 720, editorHeight);
      return new DOMRect();
    });
    const onAnchorUnavailable = vi.fn();
    render(
      <ContentPassageEditor
        anchor={anchor}
        docked={false}
        footerRef={{ current: footer }}
        onAnchorUnavailable={onAnchorUnavailable}
      >
        {() => <textarea aria-label="Translation" defaultValue="Unsaved draft" />}
      </ContentPassageEditor>,
    );
    const editor = screen.getByRole('dialog', { name: 'Translation editor' });
    expect(Number.parseFloat(editor.style.top) + editorHeight).toBeLessThanOrEqual(footerTop - 12);

    anchorTop = window.innerHeight + 32;
    fireEvent.scroll(reader);

    expect(Number.parseFloat(editor.style.top)).toBeGreaterThanOrEqual(12);
    expect(Number.parseFloat(editor.style.top) + editorHeight).toBeLessThanOrEqual(footerTop - 12);
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Unsaved draft');
    expect(onAnchorUnavailable).not.toHaveBeenCalled();
  });
});
