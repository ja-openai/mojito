import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { VisibleTextRenderer } from './VisibleTextRenderer';

describe('VisibleTextRenderer', () => {
  it('renders protected ICU and HTML tokens without mounting a textbox by default', () => {
    const { container } = render(
      <VisibleTextRenderer value="Pay <b>{price}</b> now" tokenMode="icu-html" />,
    );

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(container.querySelector('.ProseMirror')).not.toBeInTheDocument();
    expect(
      container.querySelector('.visible-text-editor__protected-token--html-tag'),
    ).toHaveTextContent('<b>');
    expect(
      container.querySelector('.visible-text-editor__protected-token--icu-placeholder'),
    ).toHaveTextContent('price');
    expect(
      container.querySelector('.visible-text-editor__protected-token--icu-placeholder'),
    ).toHaveAttribute('aria-label', 'ICU argument price');
    expect(
      container.querySelector('.visible-text-editor__protected-token--icu-placeholder'),
    ).toHaveAttribute('data-raw', '{price}');
  });

  it('uses the shared compact ICU syntax rendering', () => {
    const { container } = render(
      <VisibleTextRenderer
        value="{count, plural, one {# file} other {# files}}"
        tokenMode="icu-html"
      />,
    );

    const syntaxTokens = Array.from(
      container.querySelectorAll<HTMLElement>('.visible-text-editor__protected-token--icu-syntax'),
    );
    const icuMessage = container.querySelector('.visible-text-renderer__icu-message');
    expect(icuMessage).toBeInTheDocument();
    expect(icuMessage).toHaveTextContent('count one# fileother# files');
    expect(
      Array.from(icuMessage?.querySelectorAll('.visible-text-editor__icu-editable-text') ?? []).map(
        (element) => element.textContent,
      ),
    ).toEqual([' file', ' files']);
    expect(
      Array.from(
        icuMessage?.querySelectorAll('.visible-text-editor__protected-token--icu-placeholder') ??
          [],
      ).map((element) => element.textContent),
    ).toEqual(['#', '#']);
    expect(syntaxTokens[0]).toHaveTextContent('count one');
    expect(syntaxTokens[0]).not.toHaveTextContent('plural');
    expect(
      syntaxTokens[0].querySelector('.visible-text-editor__icu-syntax-form'),
    ).toHaveTextContent('one');
    expect(syntaxTokens[syntaxTokens.length - 1]).toHaveClass(
      'visible-text-editor__protected-token--empty-icu-syntax',
    );
  });

  it('does not group ICU-looking text when protected token rendering is off', () => {
    const { container } = render(
      <VisibleTextRenderer
        value="{count, plural, one {# file} other {# files}}"
        showProtectedTokens={false}
        tokenMode="icu-html"
      />,
    );

    expect(container).toHaveTextContent('{count, plural, one {# file} other {# files}}');
    expect(container.querySelector('.visible-text-renderer__icu-message')).not.toBeInTheDocument();
  });

  it('renders platform placeholders in composite protected text', () => {
    const { container } = render(
      <VisibleTextRenderer value="Pay %1$@ by %.2f%%" tokenMode="icu-html" />,
    );

    expect(
      Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token--platform-placeholder'),
      ).map((token) => token.textContent),
    ).toEqual(['%1$@', '%.2f']);
  });

  it('can render protected token text without token highlighting', () => {
    const { container } = render(
      <VisibleTextRenderer
        value="Pay <b>{price}</b> now"
        tokenMode="icu-html"
        showProtectedTokens={false}
      />,
    );

    expect(container).toHaveTextContent('Pay <b>{price}</b> now');
    expect(
      container.querySelector('.visible-text-editor__protected-token'),
    ).not.toBeInTheDocument();
  });

  it('renders malformed placeholder diagnostics without mounting ProseMirror', () => {
    const { container } = render(
      <VisibleTextRenderer value="Broken %1$ placeholder" tokenMode="icu-html" marksMode="off" />,
    );

    const diagnostic = container.querySelector('.visible-text-editor__diagnostic--warning');
    expect(diagnostic).toHaveTextContent('%1$');
    expect(diagnostic).toHaveClass('visible-text-editor__diagnostic--placeholder-malformed');
    expect(diagnostic).toHaveAttribute(
      'title',
      'Placeholder-like sequence %1$ is incomplete or malformed.',
    );
    expect(container.querySelector('.ProseMirror')).not.toBeInTheDocument();
    expect(container.querySelector('.visible-text-editor__marked-char')).not.toBeInTheDocument();
  });

  it('keeps diagnostics visible when placeholder highlights are off', () => {
    const { container } = render(
      <VisibleTextRenderer
        value="Use %1ds carefully"
        tokenMode="icu-html"
        showProtectedTokens={false}
      />,
    );

    expect(
      container.querySelector('.visible-text-editor__protected-token'),
    ).not.toBeInTheDocument();
    const diagnostic = container.querySelector('.visible-text-editor__diagnostic--warning');
    expect(diagnostic).toHaveTextContent('%1ds');
    expect(diagnostic).toHaveAttribute(
      'title',
      'Platform placeholder %1d touches text; add a separator or verify the placeholder syntax.',
    );
  });

  it('uses the same auto marks behavior as the editor for repeated spaces', () => {
    const { container } = render(<VisibleTextRenderer value="Hello  world" marksMode="auto" />);

    expect(container.querySelectorAll('.visible-text-editor__marked-char--space')).toHaveLength(2);
  });

  it('can show all or no normal space marks', () => {
    const { container, rerender } = render(
      <VisibleTextRenderer value="Hello world" marksMode="all" />,
    );

    expect(container.querySelectorAll('.visible-text-editor__marked-char--space')).toHaveLength(1);

    rerender(<VisibleTextRenderer value="Hello world" marksMode="off" />);

    expect(container.querySelectorAll('.visible-text-editor__marked-char--space')).toHaveLength(0);
  });

  it('renders line-break markers before the newline they mark', () => {
    const { container } = render(
      <VisibleTextRenderer value={'Line one\nLine two'} marksMode="all" />,
    );

    const renderer = container.querySelector('.visible-text-renderer');
    const marker = renderer?.querySelector('.visible-text-editor__marker-widget--line-break');
    expect(marker).toBeInTheDocument();

    const childNodes = Array.from(renderer?.childNodes ?? []);
    const markerIndex = marker ? childNodes.indexOf(marker) : -1;
    expect(markerIndex).toBeGreaterThanOrEqual(0);
    expect(childNodes[markerIndex + 1].textContent?.startsWith('\n')).toBe(true);
  });

  it('keeps passive rendered text out of textbox semantics', () => {
    const { container } = render(
      <VisibleTextRenderer value="Hello" ariaLabel="Translation preview" />,
    );

    const renderer = container.querySelector('.visible-text-renderer');
    expect(screen.queryByRole('textbox', { name: 'Translation preview' })).not.toBeInTheDocument();
    expect(renderer).not.toHaveAttribute('aria-label');
    expect(renderer).not.toHaveAttribute('aria-multiline');
    expect(renderer).not.toHaveAttribute('aria-readonly');
  });

  it('can be focusable without becoming an editor', () => {
    const handleFocus = vi.fn();
    const { container } = render(
      <VisibleTextRenderer value="Hello" ariaLabel="Translation" onFocus={handleFocus} />,
    );

    const renderer = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.focus(renderer);

    expect(renderer).toHaveClass('visible-text-renderer');
    expect(renderer).toHaveAttribute('aria-multiline', 'true');
    expect(renderer).toHaveAttribute('aria-readonly', 'true');
    expect(container.querySelector('.ProseMirror')).not.toBeInTheDocument();
    expect(handleFocus).toHaveBeenCalled();
  });
});
