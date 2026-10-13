import type { MF2Message } from '@mojito-mf2/core';
import { formatMessageToParts, FunctionRegistry } from '@mojito-mf2/core/formatter';
import { createIntlFunctionRegistry } from '@mojito-mf2/core/intl';
import { renderMF2Parts } from '@mojito-mf2/react';
import { analyzeModel, type MessageContract, type ValueType } from '@mojito-mf2/tooling';
import { type ReactNode, useId, useMemo, useState } from 'react';

const functions = createIntlFunctionRegistry(FunctionRegistry, { timeZone: 'UTC' });

type Props = {
  source: MF2Message;
  target: MF2Message | null;
  locale: string;
};

/** Interactive, explicitly sampled output. Application components are never executed here. */
export function Mf2RuntimePreview(props: Props) {
  const [open, setOpen] = useState(false);
  return (
    <details
      className="mf2-runtime-preview"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary>Preview with sample values</summary>
      {open ? <SamplePreview {...props} /> : null}
    </details>
  );
}

function SamplePreview({ source, target, locale }: Props) {
  const analysis = useMemo(() => analyzeModel(source), [source]);
  if (!analysis.ok || !analysis.contract) {
    return (
      <p role="status" className="mf2-issue mf2-issue-warning">
        Preview unavailable. {analysis.diagnostics[0]?.message}
      </p>
    );
  }
  return (
    <SampleValues
      key={JSON.stringify(analysis.contract.inputs)}
      contract={analysis.contract}
      target={target}
      locale={locale}
    />
  );
}

function initialValue(types: ValueType[]) {
  if (types.includes('boolean') && types.includes('string')) return 'Sample';
  if (types.includes('date')) return '2024-01-15T12:00:00Z';
  if (types.includes('number') || types.includes('bigint')) return '1';
  if (types.includes('string')) return 'Sample';
  if (types.includes('boolean')) return 'false';
  return '';
}

function inputValue(value: string, types: ValueType[]) {
  if (types.includes('string')) return value;
  if (types.includes('boolean') && (value === 'true' || value === 'false')) return value === 'true';
  if (types.includes('number') && value.trim() && Number.isFinite(Number(value)))
    return Number(value);
  return value;
}

function SampleValues({
  contract,
  target,
  locale,
}: Omit<Props, 'source'> & { contract: MessageContract }) {
  const id = useId();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      Object.entries(contract.inputs).map(([name, types]) => [name, initialValue(types)]),
    ),
  );
  const components = useMemo(
    () =>
      Object.fromEntries(
        Object.entries(contract.bindings).map(([name, kinds]) => [
          name,
          previewBinding(name, kinds),
        ]),
      ),
    [contract.bindings],
  );
  const result = useMemo(() => {
    if (!target) return { content: null, error: 'The translation could not be parsed.' };
    try {
      // Keep editable text intact (including intermediate decimals and signs).
      // Coerce only the formatting arguments whose contracts require it.
      const args = Object.fromEntries(
        Object.entries(contract.inputs).map(([name, types]) => [
          name,
          inputValue(values[name] ?? '', types),
        ]),
      );
      const formatted = formatMessageToParts(target, args, {
        locale,
        functions,
        bidiIsolation: 'default',
      });
      if (formatted.hasErrors)
        return { content: null, error: formatted.errors.map((error) => error.message).join(' ') };
      return { content: renderMF2Parts(formatted.parts, components), error: null };
    } catch (error) {
      return {
        content: null,
        error: error instanceof Error ? error.message : 'Preview unavailable.',
      };
    }
  }, [target, values, locale, components, contract.inputs]);
  return (
    <>
      <p className="settings-hint">
        Sample output in UTC. Custom components use labelled placeholders; this does not verify
        every message branch.
      </p>
      <div className="mf2-preview-row">
        <output aria-label="Formatted message" aria-live="polite" dir="auto" lang={locale}>
          {result.error ? (
            <span role="status" className="mf2-issue mf2-issue-warning">
              {result.error}
            </span>
          ) : (
            result.content
          )}
        </output>
        <div className="mf2-args">
          {Object.keys(contract.inputs).map((name, index) => (
            <label key={name} htmlFor={`${id}-${index}`}>
              <span>{name}</span>
              <input
                id={`${id}-${index}`}
                value={values[name] ?? ''}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  setValues((current) => ({ ...current, [name]: value }));
                }}
              />
            </label>
          ))}
        </div>
      </div>
    </>
  );
}

function previewBinding(name: string, kinds: string[]): (children: ReactNode) => ReactNode {
  if (kinds.length === 1 && kinds[0] === 'standalone') {
    if (name === 'br') return () => <br />;
    return () => (
      <span className="mf2-preview-component" title={`Component ${name} (preview only)`}>
        [{name}]
      </span>
    );
  }
  if (name === 'strong' || name === 'b') return (children) => <strong>{children}</strong>;
  if (name === 'em' || name === 'i') return (children) => <em>{children}</em>;
  if (name === 'code') return (children) => <code>{children}</code>;
  return (children) => (
    <span className="mf2-preview-component" title={`Component ${name} (preview only)`}>
      {children ?? `[${name}]`}
    </span>
  );
}
