import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FindReplaceBar } from './FindReplaceBar';
import {
  FIND_REPLACE_FIND_HISTORY_STORAGE_KEY,
  FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY,
} from './findReplaceHistory';

describe('FindReplaceBar', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('shows previous find and replace values as input history', () => {
    window.localStorage.setItem(
      FIND_REPLACE_FIND_HISTORY_STORAGE_KEY,
      JSON.stringify(['old target']),
    );
    window.localStorage.setItem(
      FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY,
      JSON.stringify(['old replacement']),
    );

    renderFindReplaceBar();

    fireEvent.click(screen.getByRole('button', { name: 'Show find history' }));
    expect(screen.getByRole('button', { name: 'old target' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show replace history' }));
    expect(screen.getByRole('button', { name: 'old replacement' })).toBeInTheDocument();
  });

  it('records submitted find and replace values for later reuse', () => {
    const onSubmit = vi.fn();
    renderFindReplaceBar({
      findText: 'new target',
      replaceText: 'new replacement',
      onSubmit,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(
      JSON.parse(window.localStorage.getItem(FIND_REPLACE_FIND_HISTORY_STORAGE_KEY) ?? '[]'),
    ).toEqual(['new target']);
    expect(
      JSON.parse(window.localStorage.getItem(FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY) ?? '[]'),
    ).toEqual(['new replacement']);
  });

  it('records history when the secondary submit action is used', () => {
    const onSecondarySubmit = vi.fn();
    renderFindReplaceBar({
      findText: 'single target',
      replaceText: 'single replacement',
      secondarySubmitLabel: 'Replace',
      onSecondarySubmit,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Replace' }));

    expect(onSecondarySubmit).toHaveBeenCalledTimes(1);
    expect(
      JSON.parse(window.localStorage.getItem(FIND_REPLACE_FIND_HISTORY_STORAGE_KEY) ?? '[]'),
    ).toEqual(['single target']);
    expect(
      JSON.parse(window.localStorage.getItem(FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY) ?? '[]'),
    ).toEqual(['single replacement']);
  });

  it('clears find and replace input values', () => {
    const onFindTextChange = vi.fn();
    const onReplaceTextChange = vi.fn();
    renderFindReplaceBar({
      findText: 'target text',
      replaceText: 'replacement text',
      onFindTextChange,
      onReplaceTextChange,
    });

    fireEvent.click(screen.getByRole('button', { name: 'Clear find text' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear replace text' }));

    expect(onFindTextChange).toHaveBeenCalledWith('');
    expect(onReplaceTextChange).toHaveBeenCalledWith('');
  });

  it('cycles find history with arrow keys', () => {
    window.localStorage.setItem(
      FIND_REPLACE_FIND_HISTORY_STORAGE_KEY,
      JSON.stringify(['newest target', 'older target']),
    );
    const onFindTextChange = vi.fn();
    renderFindReplaceBar({ findText: 'newest target', onFindTextChange });

    fireEvent.keyDown(screen.getByLabelText('Find'), { key: 'ArrowDown' });
    expect(onFindTextChange).toHaveBeenCalledWith('older target');

    fireEvent.keyDown(screen.getByLabelText('Find'), { key: 'ArrowUp' });
    expect(onFindTextChange).toHaveBeenLastCalledWith('older target');
  });

  it('cycles replace history with arrow keys', () => {
    window.localStorage.setItem(
      FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY,
      JSON.stringify(['newest replacement', 'older replacement']),
    );
    const onReplaceTextChange = vi.fn();
    renderFindReplaceBar({ replaceText: '', onReplaceTextChange });

    fireEvent.keyDown(screen.getByLabelText('Replace'), { key: 'ArrowDown' });
    expect(onReplaceTextChange).toHaveBeenCalledWith('newest replacement');
  });

  it('renders optional classic find and replace toggles', () => {
    const onRegexChange = vi.fn();
    const onWholeWordChange = vi.fn();
    const onPreserveCaseChange = vi.fn();

    renderFindReplaceBar({
      regex: true,
      wholeWord: true,
      preserveCase: false,
      showRegex: true,
      showWholeWord: true,
      showPreserveCase: true,
      onRegexChange,
      onWholeWordChange,
      onPreserveCaseChange,
    });

    expect(screen.getByRole('checkbox', { name: 'Regex' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Whole words' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Preserve case' })).not.toBeChecked();

    fireEvent.click(screen.getByRole('checkbox', { name: 'Whole words' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Preserve case' }));

    expect(onWholeWordChange).toHaveBeenCalledWith(false);
    expect(onPreserveCaseChange).toHaveBeenCalledWith(true);
  });
});

function renderFindReplaceBar(overrides: Partial<Parameters<typeof FindReplaceBar>[0]> = {}) {
  return render(
    <FindReplaceBar
      findText=""
      replaceText=""
      matchCase
      submitLabel="Apply"
      onFindTextChange={vi.fn()}
      onReplaceTextChange={vi.fn()}
      onMatchCaseChange={vi.fn()}
      onSubmit={vi.fn()}
      {...overrides}
    />,
  );
}
