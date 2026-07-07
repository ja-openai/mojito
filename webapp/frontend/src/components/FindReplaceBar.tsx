import './find-replace-bar.css';

import type { FocusEvent, KeyboardEvent, ReactNode } from 'react';
import { useId, useState } from 'react';

import {
  FIND_REPLACE_FIND_HISTORY_STORAGE_KEY,
  FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY,
  readFindReplaceHistory,
  recordFindReplaceHistory,
} from './findReplaceHistory';

type FindReplaceBarClassNames = {
  root?: string;
  fields?: string;
  field?: string;
  fieldHeading?: string;
  fieldControl?: string;
  clearButton?: string;
  historyButton?: string;
  historyPanel?: string;
  historyOption?: string;
  options?: string;
  check?: string;
  actionPrefix?: string;
  actions?: string;
  projectActions?: string;
  button?: string;
  error?: string;
};

export type FindReplaceBarProps = {
  findText: string;
  replaceText: string;
  matchCase: boolean;
  regex?: boolean;
  wholeWord?: boolean;
  preserveCase?: boolean;
  showRegex?: boolean;
  showWholeWord?: boolean;
  showPreserveCase?: boolean;
  disabled?: boolean;
  submitDisabled?: boolean;
  findInvalid?: boolean;
  replaceInvalid?: boolean;
  findDescriptionId?: string;
  replaceDescriptionId?: string;
  error?: string | null;
  errorId?: string;
  submitLabel: string;
  submitTitle?: string;
  secondarySubmitLabel?: string;
  secondarySubmitDisabled?: boolean;
  secondarySubmitTitle?: string;
  findAdornment?: ReactNode;
  replaceAdornment?: ReactNode;
  actionPrefix?: ReactNode;
  actionSuffix?: ReactNode;
  projectActions?: ReactNode;
  classNames?: FindReplaceBarClassNames;
  onFindTextChange: (value: string) => void;
  onReplaceTextChange: (value: string) => void;
  onMatchCaseChange: (value: boolean) => void;
  onRegexChange?: (value: boolean) => void;
  onWholeWordChange?: (value: boolean) => void;
  onPreserveCaseChange?: (value: boolean) => void;
  onSubmit: () => void;
  onSecondarySubmit?: () => void;
};

const DEFAULT_CLASS_NAMES: Required<FindReplaceBarClassNames> = {
  root: 'find-replace-bar',
  fields: 'find-replace-bar__fields',
  field: 'find-replace-bar__field',
  fieldHeading: 'find-replace-bar__field-heading',
  fieldControl: 'find-replace-bar__field-control',
  clearButton: 'find-replace-bar__clear-button',
  historyButton: 'find-replace-bar__history-button',
  historyPanel: 'find-replace-bar__history-panel',
  historyOption: 'find-replace-bar__history-option',
  options: 'find-replace-bar__options',
  check: 'find-replace-bar__check',
  actionPrefix: 'find-replace-bar__action-prefix',
  actions: 'find-replace-bar__actions',
  projectActions: 'find-replace-bar__project-actions',
  button: 'find-replace-bar__button',
  error: 'find-replace-bar__error',
};

export function FindReplaceBar({
  findText,
  replaceText,
  matchCase,
  regex = false,
  wholeWord = false,
  preserveCase = false,
  showRegex = false,
  showWholeWord = false,
  showPreserveCase = false,
  disabled = false,
  submitDisabled = false,
  findInvalid = false,
  replaceInvalid = false,
  findDescriptionId,
  replaceDescriptionId,
  error = null,
  errorId,
  submitLabel,
  submitTitle,
  secondarySubmitLabel,
  secondarySubmitDisabled = false,
  secondarySubmitTitle,
  findAdornment,
  replaceAdornment,
  actionPrefix,
  actionSuffix,
  projectActions,
  classNames,
  onFindTextChange,
  onReplaceTextChange,
  onMatchCaseChange,
  onRegexChange,
  onWholeWordChange,
  onPreserveCaseChange,
  onSubmit,
  onSecondarySubmit,
}: FindReplaceBarProps) {
  const findInputId = useId();
  const replaceInputId = useId();
  const [findHistory, setFindHistory] = useState(() =>
    readFindReplaceHistory(FIND_REPLACE_FIND_HISTORY_STORAGE_KEY),
  );
  const [replaceHistory, setReplaceHistory] = useState(() =>
    readFindReplaceHistory(FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY),
  );
  const [openHistory, setOpenHistory] = useState<'find' | 'replace' | null>(null);
  const getClassName = (key: keyof FindReplaceBarClassNames) =>
    [DEFAULT_CLASS_NAMES[key], classNames?.[key]].filter(Boolean).join(' ');

  const recordCurrentHistory = () => {
    setFindHistory(recordFindReplaceHistory(FIND_REPLACE_FIND_HISTORY_STORAGE_KEY, findText));
    setReplaceHistory(
      recordFindReplaceHistory(FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY, replaceText),
    );
  };

  const handleSubmit = () => {
    if (disabled || submitDisabled) {
      return;
    }
    recordCurrentHistory();
    onSubmit();
  };

  const handleSecondarySubmit = () => {
    if (disabled || secondarySubmitDisabled || !onSecondarySubmit) {
      return;
    }
    recordCurrentHistory();
    onSecondarySubmit();
  };

  const handleHistoryBlur = (event: FocusEvent<HTMLDivElement>) => {
    if (event.relatedTarget && event.currentTarget.contains(event.relatedTarget)) {
      return;
    }
    setOpenHistory(null);
  };

  const selectHistoryValue = (value: string, onChange: (nextValue: string) => void) => {
    onChange(value);
    setOpenHistory(null);
  };

  const clearValue = (onChange: (nextValue: string) => void) => {
    onChange('');
    setOpenHistory(null);
  };

  return (
    <section className={getClassName('root')} aria-label="Find and replace">
      <div className={getClassName('fields')}>
        <div className={getClassName('field')} onBlur={handleHistoryBlur}>
          <label className={getClassName('fieldHeading')} htmlFor={findInputId}>
            <span>Find</span>
            {findAdornment}
          </label>
          <div className={getClassName('fieldControl')}>
            <input
              id={findInputId}
              type="text"
              value={findText}
              disabled={disabled}
              placeholder="Target text to find"
              aria-label="Find"
              aria-invalid={findInvalid}
              aria-describedby={findDescriptionId}
              onChange={(event) => onFindTextChange(event.target.value)}
              onKeyDown={(event) =>
                handleHistoryKeyDown(event, findHistory, findText, onFindTextChange)
              }
            />
            {findText ? (
              <button
                type="button"
                className={getClassName('clearButton')}
                disabled={disabled}
                aria-label="Clear find text"
                title="Clear find text"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => clearValue(onFindTextChange)}
              >
                ×
              </button>
            ) : null}
            {findHistory.length > 0 ? (
              <>
                <button
                  type="button"
                  className={getClassName('historyButton')}
                  disabled={disabled}
                  aria-label="Show find history"
                  aria-expanded={openHistory === 'find'}
                  onClick={() => setOpenHistory((current) => (current === 'find' ? null : 'find'))}
                >
                  ▾
                </button>
                {openHistory === 'find' ? (
                  <div className={getClassName('historyPanel')} role="listbox">
                    {findHistory.map((value) => (
                      <button
                        key={value}
                        type="button"
                        className={getClassName('historyOption')}
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={() => selectHistoryValue(value, onFindTextChange)}
                      >
                        {value}
                      </button>
                    ))}
                  </div>
                ) : null}
              </>
            ) : null}
          </div>
        </div>
        <div className={getClassName('field')} onBlur={handleHistoryBlur}>
          <label className={getClassName('fieldHeading')} htmlFor={replaceInputId}>
            <span>Replace</span>
            {replaceAdornment}
          </label>
          <div className={getClassName('fieldControl')}>
            <input
              id={replaceInputId}
              type="text"
              value={replaceText}
              disabled={disabled}
              placeholder="Replacement text"
              aria-label="Replace"
              aria-invalid={replaceInvalid}
              aria-describedby={replaceDescriptionId}
              onChange={(event) => onReplaceTextChange(event.target.value)}
              onKeyDown={(event) =>
                handleHistoryKeyDown(event, replaceHistory, replaceText, onReplaceTextChange)
              }
            />
            {replaceText ? (
              <button
                type="button"
                className={getClassName('clearButton')}
                disabled={disabled}
                aria-label="Clear replace text"
                title="Clear replace text"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => clearValue(onReplaceTextChange)}
              >
                ×
              </button>
            ) : null}
            {replaceHistory.length > 0 ? (
              <>
                <button
                  type="button"
                  className={getClassName('historyButton')}
                  disabled={disabled}
                  aria-label="Show replace history"
                  aria-expanded={openHistory === 'replace'}
                  onClick={() =>
                    setOpenHistory((current) => (current === 'replace' ? null : 'replace'))
                  }
                >
                  ▾
                </button>
                {openHistory === 'replace' ? (
                  <div className={getClassName('historyPanel')} role="listbox">
                    {replaceHistory.map((value) => (
                      <button
                        key={value}
                        type="button"
                        className={getClassName('historyOption')}
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={() => selectHistoryValue(value, onReplaceTextChange)}
                      >
                        {value}
                      </button>
                    ))}
                  </div>
                ) : null}
              </>
            ) : null}
          </div>
        </div>
      </div>
      <div className={getClassName('options')}>
        <label className={getClassName('check')}>
          <input
            type="checkbox"
            checked={matchCase}
            disabled={disabled}
            onChange={(event) => onMatchCaseChange(event.target.checked)}
          />
          <span>Match case in target</span>
        </label>
        {showWholeWord ? (
          <label className={getClassName('check')}>
            <input
              type="checkbox"
              checked={wholeWord}
              disabled={disabled}
              onChange={(event) => onWholeWordChange?.(event.target.checked)}
            />
            <span>Whole words</span>
          </label>
        ) : null}
        {showRegex ? (
          <label className={getClassName('check')}>
            <input
              type="checkbox"
              checked={regex}
              disabled={disabled}
              onChange={(event) => onRegexChange?.(event.target.checked)}
            />
            <span>Regex</span>
          </label>
        ) : null}
        {showPreserveCase ? (
          <label className={getClassName('check')}>
            <input
              type="checkbox"
              checked={preserveCase}
              disabled={disabled}
              onChange={(event) => onPreserveCaseChange?.(event.target.checked)}
            />
            <span>Preserve case</span>
          </label>
        ) : null}
      </div>
      <div className={getClassName('actions')}>
        {actionPrefix ? <div className={getClassName('actionPrefix')}>{actionPrefix}</div> : null}
        {secondarySubmitLabel ? (
          <button
            type="button"
            className={getClassName('button')}
            disabled={disabled || secondarySubmitDisabled}
            title={secondarySubmitTitle}
            onClick={handleSecondarySubmit}
          >
            {secondarySubmitLabel}
          </button>
        ) : null}
        <button
          type="button"
          className={getClassName('button')}
          disabled={disabled || submitDisabled}
          title={submitTitle}
          onClick={handleSubmit}
        >
          {submitLabel}
        </button>
        {actionSuffix}
      </div>
      {projectActions ? (
        <div className={getClassName('projectActions')}>{projectActions}</div>
      ) : null}
      {error ? (
        <div id={errorId} className={getClassName('error')} role="alert">
          {error}
        </div>
      ) : null}
    </section>
  );
}

function handleHistoryKeyDown(
  event: KeyboardEvent<HTMLInputElement>,
  history: string[],
  currentValue: string,
  onChange: (value: string) => void,
) {
  if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') {
    return;
  }

  const nextValue = getCycledHistoryValue({
    currentValue,
    direction: event.key === 'ArrowUp' ? 'previous' : 'next',
    history,
  });
  if (nextValue == null) {
    return;
  }

  event.preventDefault();
  onChange(nextValue);
}

function getCycledHistoryValue({
  currentValue,
  direction,
  history,
}: {
  currentValue: string;
  direction: 'previous' | 'next';
  history: string[];
}) {
  if (history.length === 0) {
    return null;
  }

  const currentIndex = history.indexOf(currentValue);
  if (currentIndex === -1) {
    return history[0];
  }

  const offset = direction === 'previous' ? -1 : 1;
  return history[(currentIndex + offset + history.length) % history.length];
}
