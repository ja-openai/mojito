export type InflectionFormEditorRow = {
  id: string;
  key: string;
  value: string;
};

const INFLECTION_FORM_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]*$/u;
const DEFAULT_FORM_KEYS = ['bare.singular', 'bare.plural', 'definite.singular', 'count.other'];

let nextInflectionFormRowId = 0;

export function parseInflectionFormRows(formsJson: string): InflectionFormEditorRow[] {
  const parsed = parseFormsObject(formsJson);
  return Object.entries(parsed).map(([key, value]) => createInflectionFormRow(key, value));
}

export function createEmptyInflectionFormRow(
  rows: InflectionFormEditorRow[],
): InflectionFormEditorRow {
  return createInflectionFormRow(nextInflectionFormKey(rows), '');
}

export function formatInflectionFormRowsAsJson(rows: InflectionFormEditorRow[]): string {
  const values: Record<string, string> = {};
  for (const row of rows) {
    values[row.key] = row.value;
  }
  return JSON.stringify(values, null, 2);
}

export function serializeInflectionFormRows(rows: InflectionFormEditorRow[]): string {
  const validationError = validateInflectionFormRows(rows);
  if (validationError) {
    throw new Error(validationError);
  }

  const values: Record<string, string> = {};
  for (const row of rows) {
    values[row.key.trim()] = row.value;
  }
  return JSON.stringify(values);
}

export function validateInflectionFormRows(rows: InflectionFormEditorRow[]): string | null {
  const keys = new Set<string>();
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) {
      return 'Form key is required.';
    }
    if (!INFLECTION_FORM_KEY_PATTERN.test(key)) {
      return `Unsupported inflection form key: ${key}.`;
    }
    if (keys.has(key)) {
      return `Duplicate form key: ${key}.`;
    }
    if (!row.value.trim()) {
      return `Form ${key} must have text.`;
    }
    keys.add(key);
  }
  return null;
}

function parseFormsObject(formsJson: string): Record<string, string> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(formsJson) as unknown;
  } catch {
    throw new Error('Forms JSON must be valid JSON.');
  }
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('Forms JSON must be a JSON object.');
  }

  const values: Record<string, string> = {};
  for (const [key, value] of Object.entries(parsed)) {
    if (typeof value !== 'string') {
      throw new Error(`Forms JSON.${key} must be a string.`);
    }
    values[key] = value;
  }
  return values;
}

function createInflectionFormRow(key: string, value: string): InflectionFormEditorRow {
  nextInflectionFormRowId += 1;
  return {
    id: `inflection-form-${nextInflectionFormRowId}`,
    key,
    value,
  };
}

function nextInflectionFormKey(rows: InflectionFormEditorRow[]): string {
  const existingKeys = new Set(rows.map((row) => row.key.trim()).filter(Boolean));
  for (const defaultKey of DEFAULT_FORM_KEYS) {
    if (!existingKeys.has(defaultKey)) {
      return defaultKey;
    }
  }

  let index = 1;
  while (existingKeys.has(`form.${index}`)) {
    index += 1;
  }
  return `form.${index}`;
}
