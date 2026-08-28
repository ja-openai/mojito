import { diagnosticsFor, parseMf2 } from './model';

export function mf2TranslationErrorCount({
  locale,
  source,
  target,
}: {
  locale: string;
  source: string;
  target: string;
}) {
  const sourceParsed = parseMf2(source, {}, locale, { includeRuntimeDiagnostics: false });
  const targetParsed = parseMf2(target, {}, locale, { includeRuntimeDiagnostics: false });
  return diagnosticsFor(
    sourceParsed.model,
    targetParsed.model,
    targetParsed.diagnostics,
    locale,
    sourceParsed.diagnostics,
  ).filter((diagnostic) => diagnostic.severity === 'error').length;
}
