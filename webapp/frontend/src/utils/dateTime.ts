type DateInput = string | number | Date | null | undefined;

const LOCAL_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone || 'local';

function toDate(value: DateInput): Date | null {
  if (value == null) {
    return null;
  }
  const parsed = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return parsed;
}

export function formatLocalDate(value: DateInput, fallback = '—'): string {
  const parsed = toDate(value);
  if (!parsed) {
    return fallback;
  }
  return parsed.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

export function formatLocalDateTime(value: DateInput, fallback = '—'): string {
  const parsed = toDate(value);
  if (!parsed) {
    return fallback;
  }
  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatUtcDateTime(value: DateInput, fallback = '—'): string {
  const parsed = toDate(value);
  if (!parsed) {
    return fallback;
  }
  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'UTC',
    timeZoneName: 'short',
  });
}

export function getLocalAndUtcDateTimeTooltip(value: DateInput): string | undefined {
  const parsed = toDate(value);
  if (!parsed) {
    return undefined;
  }
  const local = parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
  });
  const utc = formatUtcDateTime(parsed, '');
  return `Local (${LOCAL_TIME_ZONE}): ${local}\nUTC: ${utc}`;
}

export function toDateTimeLocalInputValue(value: DateInput): string {
  const parsed = toDate(value);
  if (!parsed) {
    return '';
  }
  const pad = (num: number) => String(num).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

export function localDateTimeInputToIso(value: string | null | undefined): string | null {
  const parsed = toDate(value);
  if (!parsed) {
    return null;
  }
  return parsed.toISOString();
}
