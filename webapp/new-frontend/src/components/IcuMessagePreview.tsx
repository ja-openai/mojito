import './icu-message-preview.css';

import { useEffect, useMemo, useState } from 'react';

import type { IcuParameterDescriptor } from '../utils/icuMessageFormat';
import {
  buildIcuExampleValueSets,
  mergeValues,
  parseIcuMessage,
  renderIcuWithValues,
} from '../utils/icuMessageFormat';

export type IcuAiSuggestionRequest = {
  message: string;
  locale: string;
  parameters: IcuParameterDescriptor[];
  values: Record<string, string>;
};

export type IcuMessagePreviewProps = {
  message?: string;
  locale?: string;
  onMessageChange?: (value: string) => void;
  onLocaleChange?: (value: string) => void;
  initialMessage?: string;
  initialLocale?: string;
  showMessageEditor?: boolean;
  showLocaleInput?: boolean;
  requestAiValues?: (request: IcuAiSuggestionRequest) => Promise<Record<string, string>>;
};

type ParseState = {
  ast: ReturnType<typeof parseIcuMessage>['ast'] | null;
  parameters: IcuParameterDescriptor[];
  error: string | null;
};

const DEFAULT_MESSAGE =
  '{name} has {count, plural, =0 {no tasks} one {# task} other {# tasks}} due by {dueDate, date, medium}.';

export function IcuMessagePreview({
  message: controlledMessage,
  locale: controlledLocale,
  onMessageChange,
  onLocaleChange,
  initialMessage = DEFAULT_MESSAGE,
  initialLocale = 'en',
  showMessageEditor = true,
  showLocaleInput = true,
  requestAiValues,
}: IcuMessagePreviewProps) {
  const [internalMessage, setInternalMessage] = useState(initialMessage);
  const [internalLocale, setInternalLocale] = useState(initialLocale);
  const [selectedPresetId, setSelectedPresetId] = useState<string | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [aiError, setAiError] = useState<string | null>(null);
  const [isFetchingAiValues, setIsFetchingAiValues] = useState(false);
  const message = controlledMessage ?? internalMessage;
  const normalizedMessage = typeof message === 'string' ? message : '';
  const locale = controlledLocale ?? internalLocale;

  const setMessage = (nextMessage: string) => {
    if (onMessageChange) {
      onMessageChange(nextMessage);
      return;
    }
    setInternalMessage(nextMessage);
  };

  const setLocale = (nextLocale: string) => {
    if (onLocaleChange) {
      onLocaleChange(nextLocale);
      return;
    }
    setInternalLocale(nextLocale);
  };

  const parseState = useMemo<ParseState>(() => {
    if (!normalizedMessage.trim()) {
      return {
        ast: [],
        parameters: [],
        error: null,
      };
    }
    try {
      const parsed = parseIcuMessage(normalizedMessage);
      return {
        ast: parsed.ast,
        parameters: parsed.parameters,
        error: null,
      };
    } catch (error: unknown) {
      return {
        ast: null,
        parameters: [],
        error: error instanceof Error ? error.message : 'Unable to parse ICU message.',
      };
    }
  }, [normalizedMessage]);

  const presets = useMemo(() => buildIcuExampleValueSets(parseState.parameters), [parseState.parameters]);

  useEffect(() => {
    if (parseState.error) {
      setSelectedPresetId(null);
      setValues({});
      return;
    }

    const firstPreset = presets[0];
    const nextSelectedPresetId =
      selectedPresetId && presets.some((preset) => preset.id === selectedPresetId)
        ? selectedPresetId
        : (firstPreset?.id ?? null);

    const activePreset =
      presets.find((preset) => preset.id === nextSelectedPresetId) ??
      firstPreset ?? {
        id: 'empty',
        label: 'Empty',
        values: {},
      };

    setSelectedPresetId(nextSelectedPresetId);
    setValues((previous) => {
      if (parseState.parameters.length === 0) {
        return {};
      }
      const merged = mergeValues(activePreset.values, previous, parseState.parameters);
      parseState.parameters.forEach((parameter) => {
        if (!(parameter.name in merged)) {
          merged[parameter.name] = activePreset.values[parameter.name] ?? '';
        }
      });
      return merged;
    });
  }, [parseState.error, parseState.parameters, presets, selectedPresetId]);

  const selectedOutput = useMemo(() => {
    if (parseState.error || !parseState.ast) {
      return {
        value: '',
        error: parseState.error ?? 'Unable to parse message.',
      };
    }
    try {
      return {
        value: renderIcuWithValues(parseState.ast, parseState.parameters, values, locale, normalizedMessage),
        error: null,
      };
    } catch (error: unknown) {
      return {
        value: '',
        error: error instanceof Error ? error.message : 'Failed to render message.',
      };
    }
  }, [locale, parseState.ast, parseState.error, parseState.parameters, values]);

  const presetOutputs = useMemo(() => {
    const ast = parseState.ast;
    if (parseState.error || !ast) {
      return [];
    }

    return presets.map((preset) => {
      try {
        return {
          ...preset,
          output: renderIcuWithValues(ast, parseState.parameters, preset.values, locale, normalizedMessage),
          error: null,
        };
      } catch (error: unknown) {
        return {
          ...preset,
          output: '',
          error: error instanceof Error ? error.message : 'Failed to render message.',
        };
      }
    });
  }, [locale, parseState.ast, parseState.error, parseState.parameters, presets]);

  const handleSelectPreset = (presetId: string) => {
    const preset = presets.find((item) => item.id === presetId);
    if (!preset) {
      return;
    }
    setSelectedPresetId(presetId);
    setValues({ ...preset.values });
    setAiError(null);
  };

  const handleChangeValue = (name: string, nextValue: string) => {
    setValues((previous) => ({ ...previous, [name]: nextValue }));
    setAiError(null);
  };

  const handleAskAi = async () => {
    if (!requestAiValues || parseState.parameters.length === 0 || parseState.error) {
      return;
    }

    setIsFetchingAiValues(true);
    setAiError(null);
    try {
      const suggestedValues = await requestAiValues({
        message: normalizedMessage,
        locale,
        parameters: parseState.parameters,
        values,
      });
      setValues((previous) => mergeValues(previous, suggestedValues, parseState.parameters));
    } catch (error: unknown) {
      setAiError(error instanceof Error ? error.message : 'AI value suggestion failed.');
    } finally {
      setIsFetchingAiValues(false);
    }
  };

  return (
    <div className="icu-preview">
      <section className="icu-preview__section">
        {showMessageEditor ? (
          <>
            <div className="icu-preview__label-row">
              <label className="form-label" htmlFor="icu-message-input">
                ICU message
              </label>
            </div>
            <textarea
              id="icu-message-input"
              className="input icu-preview__textarea"
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              rows={5}
              spellCheck={false}
              placeholder="e.g. {name} has {count, plural, one {# message} other {# messages}}."
            />
          </>
        ) : null}
        {showLocaleInput ? (
          <div className="icu-preview__locale-row">
            <label className="form-label" htmlFor="icu-locale-input">
              Locale
            </label>
            <input
              id="icu-locale-input"
              className="input icu-preview__locale-input"
              type="text"
              value={locale}
              onChange={(event) => setLocale(event.target.value)}
              placeholder="en"
            />
          </div>
        ) : null}
        {parseState.error ? (
          <div className="alert alert--error">Parse error: {parseState.error}</div>
        ) : parseState.parameters.length === 0 ? (
          <div className="alert alert--muted">No parameters detected.</div>
        ) : null}
      </section>

      {parseState.parameters.length > 0 && !parseState.error ? (
        <section className="icu-preview__section">
          <h2 className="page-title icu-preview__heading">Detected parameters</h2>
          <div className="icu-preview__parameter-table">
            {parseState.parameters.map((parameter) => (
              <div key={parameter.name} className="icu-preview__parameter-row">
                <div className="icu-preview__parameter-meta">
                  <code>{parameter.name}</code>
                  <span className="icu-preview__parameter-kinds">{parameter.kinds.join(', ')}</span>
                  {parameter.selectOptions.length > 0 ? (
                    <span className="icu-preview__parameter-options">
                      select: {parameter.selectOptions.join(', ')}
                    </span>
                  ) : null}
                  {parameter.pluralOptions.length > 0 ? (
                    <span className="icu-preview__parameter-options">
                      plural: {parameter.pluralOptions.join(', ')}
                    </span>
                  ) : null}
                </div>
                <div className="icu-preview__parameter-input">
                  <ParameterInput
                    parameter={parameter}
                    value={values[parameter.name] ?? ''}
                    onChange={(nextValue) => handleChangeValue(parameter.name, nextValue)}
                  />
                </div>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      <section className="icu-preview__section">
        <h2 className="page-title icu-preview__heading">Rendered output</h2>
        {selectedOutput.error ? (
          <div className="alert alert--error">{selectedOutput.error}</div>
        ) : (
          <pre className="icu-preview__output">{selectedOutput.value}</pre>
        )}
      </section>

      {presets.length > 0 && !parseState.error ? (
        <section className="icu-preview__section">
          <div className="icu-preview__preset-header">
            <h2 className="page-title icu-preview__heading">Example parameter sets</h2>
            {requestAiValues ? (
              <button
                type="button"
                className="btn"
                onClick={() => {
                  void handleAskAi();
                }}
                disabled={isFetchingAiValues || parseState.parameters.length === 0}
              >
                {isFetchingAiValues ? 'Asking AI…' : 'Ask AI for values'}
              </button>
            ) : null}
          </div>
          {aiError ? <div className="alert alert--error">{aiError}</div> : null}
          <div className="icu-preview__preset-list">
            {presetOutputs.map((preset) => (
              <label key={preset.id} className="icu-preview__preset-item">
                <input
                  type="radio"
                  name="icu-preset"
                  checked={selectedPresetId === preset.id}
                  onChange={() => handleSelectPreset(preset.id)}
                />
                <span className="icu-preview__preset-content">
                  <span className="icu-preview__preset-title">{preset.label}</span>
                  <code className="icu-preview__preset-values">{JSON.stringify(preset.values)}</code>
                  {preset.error ? (
                    <span className="icu-preview__preset-error">{preset.error}</span>
                  ) : (
                    <span className="icu-preview__preset-output">{preset.output}</span>
                  )}
                </span>
              </label>
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
}

function ParameterInput({
  parameter,
  value,
  onChange,
}: {
  parameter: IcuParameterDescriptor;
  value: string;
  onChange: (value: string) => void;
}) {
  if (parameter.selectOptions.length > 0) {
    return (
      <select className="input" value={value} onChange={(event) => onChange(event.target.value)}>
        {parameter.selectOptions.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    );
  }

  if (parameter.kinds.includes('date') || parameter.kinds.includes('time')) {
    return (
      <input
        className="input"
        type="datetime-local"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    );
  }

  if (parameter.kinds.includes('number') || parameter.kinds.includes('plural')) {
    return (
      <input
        className="input"
        type="number"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    );
  }

  return (
    <input
      className="input"
      type="text"
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}
