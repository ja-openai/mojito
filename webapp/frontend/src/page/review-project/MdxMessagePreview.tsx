import { type MF2Message } from '@mojito-mf2/core';
import { formatMessageToParts, FunctionRegistry } from '@mojito-mf2/core/formatter';
import { createIntlFunctionRegistry } from '@mojito-mf2/core/intl';
import { renderMF2Parts } from '@mojito-mf2/react';
import { type ReactNode, useMemo } from 'react';

import { parseMf2 } from '../../components/mf2/model';

const functions = createIntlFunctionRegistry(FunctionRegistry, { timeZone: 'UTC' });

// These bindings belong to Mojito, not to uploaded MDX or catalog data. Links
// show their label without navigating or running the application's handlers.
const previewMarkupRenderers = {
  strong: (children: ReactNode) => <strong>{children}</strong>,
  em: (children: ReactNode) => <em>{children}</em>,
  termsLink: (children: ReactNode) => (
    <span className="review-project-document__message-link" title="Terms link (preview only)">
      {children}
    </span>
  ),
  br: (children: ReactNode) => {
    if (children !== null) throw new Error('The br preview markup must be standalone.');
    return <br />;
  },
};

type Props = {
  value: string;
  args?: Record<string, string | number | boolean> | null;
  locale?: string | null;
};

// This adapter formats an MF2 message with declared samples. It never evaluates
// uploaded MDX or site component code, or interprets catalog text as HTML.
export function MdxMessagePreview({ value, args, locale }: Props) {
  const preview = useMemo(() => {
    try {
      if (!locale) throw new Error('The message language is unavailable.');
      if (
        !args ||
        Object.keys(args).length > 32 ||
        Object.values(args).some(
          (arg) =>
            !['string', 'number', 'boolean'].includes(typeof arg) ||
            (typeof arg === 'number' && !Number.isFinite(arg)),
        )
      )
        throw new Error('The sample inputs are unavailable or unsupported.');
      const parsed = parseMf2(value, args, locale, { includeRuntimeDiagnostics: false });
      if (!parsed.model || parsed.diagnostics.some((item) => item.severity === 'error')) {
        throw new Error('The message could not be parsed.');
      }
      const result = formatMessageToParts(parsed.model.rustModel as MF2Message, args, {
        locale,
        functions,
      });
      if (result.hasErrors)
        throw new Error('The message could not be formatted with these sample inputs.');
      return {
        content: renderMF2Parts(result.parts, previewMarkupRenderers, { bidiIsolation: 'default' }),
        error: null,
      };
    } catch (error) {
      return {
        content: value,
        error: error instanceof Error ? error.message : 'The message could not be formatted.',
      };
    }
  }, [value, args, locale]);
  return (
    <>
      <p>{preview.content}</p>
      {preview.error ? (
        <span className="review-project-document__warning">
          Message preview unavailable. {preview.error}
        </span>
      ) : (
        <span
          className="review-project-document__status"
          title={Object.entries(args ?? {})
            .map(([name, arg]) => `${name}: ${String(arg)}`)
            .join(', ')}
        >
          Sample values
        </span>
      )}
    </>
  );
}
