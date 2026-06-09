const MF2_PLACEHOLDER_NAME_PATTERN = /\$([\p{L}_][\p{L}\p{N}_-]*)/gu;
const MF2_TARGET_PLACEHOLDER_PATTERN = /\{\s*\$([\p{L}_][\p{L}\p{N}_-]*)\s*\}/gu;
const MF2_COMPLETION_TRIGGER_PATTERN = /(?:\{\$?|\$)([\p{L}\p{N}_-]*)$/u;
const MAX_COMPLETION_OPTIONS = 6;

export type Mf2CompletionState = {
  from: number;
  query: string;
  selectedIndex: number;
  to: number;
};

export type Mf2Diagnostic = {
  severity: 'error' | 'warning';
  message: string;
};

export function getSourcePlaceholderNames(source: string | null | undefined) {
  return getUniquePlaceholderNames(source ?? '', MF2_PLACEHOLDER_NAME_PATTERN);
}

export function getMf2DeclarationPlaceholderNames(source: string | null | undefined) {
  return getSourcePlaceholderNames(
    (source ?? '')
      .split('\n')
      .filter((line) => /^\s*\.(?:input|local)\b/u.test(line))
      .join('\n'),
  );
}

export function getLiteralSourcePlaceholderNames(source: string | null | undefined) {
  const bodyText = (source ?? '')
    .split('\n')
    .filter((line) => !/^\s*\.(?:input|local|match)\b/u.test(line))
    .join('\n');

  return getTargetPlaceholderNames(bodyText);
}

function getUniquePlaceholderNames(source: string, pattern: RegExp) {
  const names: string[] = [];
  const seen = new Set<string>();

  for (const match of source.matchAll(pattern)) {
    const name = match[1];
    if (!seen.has(name)) {
      seen.add(name);
      names.push(name);
    }
  }

  return names;
}

export function getTargetPlaceholderNames(target: string) {
  return Array.from(target.matchAll(MF2_TARGET_PLACEHOLDER_PATTERN), (match) => match[1]);
}

export function getCompletionOptions(placeholderNames: string[], query: string) {
  const normalizedQuery = query.toLocaleLowerCase();
  return placeholderNames
    .filter((name) => name.toLocaleLowerCase().startsWith(normalizedQuery))
    .slice(0, MAX_COMPLETION_OPTIONS);
}

export function getNextMf2Completion({
  disabled,
  placeholderNames,
  selectedIndex = 0,
  selectionEnd,
  selectionStart,
  text,
}: {
  disabled: boolean;
  placeholderNames: string[];
  selectedIndex?: number;
  selectionEnd: number | null;
  selectionStart: number | null;
  text: string;
}): Mf2CompletionState | null {
  if (
    disabled ||
    placeholderNames.length === 0 ||
    selectionStart === null ||
    selectionEnd === null ||
    selectionStart !== selectionEnd
  ) {
    return null;
  }

  const beforeCaret = text.slice(0, selectionStart);
  const trigger = beforeCaret.match(MF2_COMPLETION_TRIGGER_PATTERN);
  if (!trigger || trigger.index === undefined) {
    return null;
  }

  const query = trigger[1] ?? '';
  const options = getCompletionOptions(placeholderNames, query);
  if (options.length === 0) {
    return null;
  }

  return {
    from: trigger.index,
    query,
    selectedIndex: Math.min(selectedIndex, options.length - 1),
    to: selectionStart,
  };
}

export function applyMf2Completion(
  text: string,
  completion: Mf2CompletionState,
  name: string,
): { nextSelection: number; nextValue: string } {
  const placeholder = `{$${name}}`;
  const replaceTo = text.charAt(completion.to) === '}' ? completion.to + 1 : completion.to;
  const nextValue = `${text.slice(0, completion.from)}${placeholder}${text.slice(replaceTo)}`;

  return {
    nextSelection: completion.from + placeholder.length,
    nextValue,
  };
}

function getBraceDiagnostic(value: string): Mf2Diagnostic | null {
  let depth = 0;

  for (const char of value) {
    if (char === '{') {
      depth += 1;
    } else if (char === '}') {
      depth -= 1;
      if (depth < 0) {
        return {
          severity: 'error',
          message: 'MF2 syntax: remove the extra closing brace.',
        };
      }
    }
  }

  if (depth > 0) {
    return {
      severity: 'error',
      message: 'MF2 syntax: add the missing closing brace.',
    };
  }

  return null;
}

export function getMf2DiagnosticsForPlaceholders(
  sourcePlaceholderNames: string[],
  requiredPlaceholderNames: string[],
  value: string,
): Mf2Diagnostic[] {
  const diagnostics: Mf2Diagnostic[] = [];
  const braceDiagnostic = getBraceDiagnostic(value);
  const trimmedValue = value.trim();

  if (braceDiagnostic) {
    diagnostics.push(braceDiagnostic);
  }

  if (!trimmedValue || sourcePlaceholderNames.length === 0) {
    return diagnostics;
  }

  const sourceNameSet = new Set(sourcePlaceholderNames);
  const targetNames = getTargetPlaceholderNames(value);
  const targetNameSet = new Set(targetNames);
  const unknownNames = Array.from(new Set(targetNames.filter((name) => !sourceNameSet.has(name))));
  const missingNames = requiredPlaceholderNames.filter((name) => !targetNameSet.has(name));

  if (unknownNames.length > 0) {
    diagnostics.push({
      severity: 'warning',
      message: `Unknown source placeholder ${formatPlaceholderList(unknownNames)}.`,
    });
  }

  if (missingNames.length > 0) {
    diagnostics.push({
      severity: 'warning',
      message: `Missing source placeholder ${formatPlaceholderList(missingNames)}.`,
    });
  }

  return diagnostics;
}

function formatPlaceholderList(names: string[]) {
  return names.map((name) => `{$${name}}`).join(', ');
}
