import {
  getIcuFormOptions,
  type IcuFormInsertion,
  type IcuFormOption,
} from './protectedTextTokens';

export type VisibleIcuSyntaxDisplay =
  | {
      argument: string;
      form: string;
      kind: 'argument-form';
      text: string;
    }
  | {
      form: string;
      kind: 'form';
      text: string;
    }
  | {
      kind: 'empty';
      text: '';
    };

export type VisibleIcuSyntaxLabel = {
  argument: string;
  form: string;
};

export type VisibleTextIcuMessage = {
  ariaLabel: string;
  checkedCount: number;
  formBodies: VisibleTextIcuFormBody[];
  key: string;
  label: string;
  messageEnd: number;
  messageStart: number;
  messageType: 'plural' | 'select';
  summary: string;
  totalCount: number;
};

export type VisibleTextIcuFormBody = {
  end: number;
  form: string;
  start: number;
};

export function visibleTextIcuMessageKey({
  messageEnd,
  messageStart,
  messageType,
}: {
  messageEnd: number;
  messageStart: number;
  messageType: 'plural' | 'select';
}): string {
  return `${messageType}:${messageStart}:${messageEnd}`;
}

export function visibleIcuSyntaxDisplay(raw: string): VisibleIcuSyntaxDisplay {
  const text = raw.replace(/[{}]/gu, ' ').replace(/\s+/gu, ' ').trim();
  if (!text) {
    return { kind: 'empty', text: '' };
  }

  const firstForm = /^([^,]+),\s*(?:plural|select),\s*(.+)$/u.exec(text);
  if (firstForm) {
    const argument = firstForm[1].trim();
    const form = firstForm[2].trim();
    return {
      argument,
      form,
      kind: 'argument-form',
      text: `${argument} ${form}`,
    };
  }

  return {
    form: text,
    kind: 'form',
    text,
  };
}

export function visibleIcuSyntaxLabel(raw: string): VisibleIcuSyntaxLabel | null {
  const display = visibleIcuSyntaxDisplay(raw);
  return display.kind === 'argument-form'
    ? {
        argument: display.argument,
        form: display.form,
      }
    : null;
}

export function visibleProtectedTokenText(kind: string, raw: string): string {
  if (kind === 'icu-syntax') {
    return visibleIcuSyntaxDisplay(raw).text;
  }

  if (kind === 'icu-placeholder' || kind === 'mf2-demo' || kind === 'placeholder') {
    return visibleBracePlaceholderText(raw) ?? raw;
  }

  return raw;
}

function visibleBracePlaceholderText(raw: string): string | null {
  const text = raw.trim();
  const mf2Variable = /^\{\$([^{}]+)\}$/u.exec(text);
  if (mf2Variable) {
    return mf2Variable[1].trim();
  }

  const simplePlaceholder = /^\{([^{},]+)\}$/u.exec(text);
  return simplePlaceholder ? simplePlaceholder[1].trim() : null;
}

function getIcuFormArgumentName(label: string): string {
  const separator = label.indexOf(':');
  return separator >= 0 ? label.slice(0, separator).trim() : label;
}

export function getVisibleTextIcuMessagesFromControls(
  options?: IcuFormOption[],
  exactInsertions?: IcuFormInsertion[],
): VisibleTextIcuMessage[] {
  const groups = new Map<
    string,
    { exactInsertions: IcuFormInsertion[]; options: IcuFormOption[] }
  >();

  options?.forEach((option) => {
    const key = visibleTextIcuMessageKey(option);
    const group = groups.get(key) ?? { exactInsertions: [], options: [] };
    group.options.push(option);
    groups.set(key, group);
  });

  exactInsertions?.forEach((insertion) => {
    const key = visibleTextIcuMessageKey({
      messageEnd: insertion.messageEnd,
      messageStart: insertion.messageStart,
      messageType: insertion.messageType ?? 'plural',
    });
    const group = groups.get(key) ?? { exactInsertions: [], options: [] };
    group.exactInsertions.push(insertion);
    groups.set(key, group);
  });

  return [...groups.entries()]
    .map(([key, group]) => {
      const firstOption = group.options[0];
      const firstExactInsertion = group.exactInsertions[0];
      const messageType = firstOption?.messageType ?? firstExactInsertion?.messageType ?? 'plural';
      const messageStart = firstOption?.messageStart ?? firstExactInsertion?.messageStart ?? 0;
      const messageEnd = firstOption?.messageEnd ?? firstExactInsertion?.messageEnd ?? messageStart;
      const argumentName = firstOption
        ? getIcuFormArgumentName(firstOption.label)
        : firstExactInsertion
          ? getIcuFormArgumentName(firstExactInsertion.label)
          : messageType;
      const checkedCount = group.options.filter((option) => option.checked).length;
      const formBodies = group.options
        .filter(
          (option): option is IcuFormOption & { bodyEnd: number; bodyStart: number } =>
            option.checked &&
            typeof option.bodyStart === 'number' &&
            typeof option.bodyEnd === 'number' &&
            option.bodyEnd > option.bodyStart,
        )
        .map((option) => ({
          end: option.bodyEnd,
          form: option.form,
          start: option.bodyStart,
        }))
        .sort((first, second) => first.start - second.start);
      const totalCount = group.options.length;
      const formLabel = messageType === 'select' ? 'select forms' : 'plural forms';
      const summary = totalCount > 0 ? `${checkedCount}/${totalCount}` : '';

      return {
        ariaLabel: `${argumentName} ${formLabel}${summary ? `: ${summary}` : ''}`,
        checkedCount,
        formBodies,
        key,
        label: `${argumentName} ${formLabel}`,
        messageEnd,
        messageStart,
        messageType,
        summary,
        totalCount,
      };
    })
    .sort((first, second) => first.messageStart - second.messageStart);
}

export function getVisibleTextIcuMessagesForValue(
  value: string,
  enabled = true,
): VisibleTextIcuMessage[] {
  if (!enabled) {
    return [];
  }

  const messages = getVisibleTextIcuMessagesFromControls(getIcuFormOptions(value));
  return messages.filter(
    (message, index) =>
      !messages.some(
        (candidate, candidateIndex) =>
          candidateIndex !== index &&
          candidate.messageStart <= message.messageStart &&
          candidate.messageEnd >= message.messageEnd &&
          candidate.messageEnd - candidate.messageStart > message.messageEnd - message.messageStart,
      ),
  );
}
