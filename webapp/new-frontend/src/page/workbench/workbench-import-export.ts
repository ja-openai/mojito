import {
  type ApiTextUnit,
  type ImportTextUnitBatchRow,
  searchTextUnits,
  type TextUnitSearchRequest,
} from '../../api/text-units';

export const DEFAULT_EXPORT_LIMIT = 10000;
export const MAX_EXPORT_BATCH_SIZE = 1000;
export const DEFAULT_EXPORT_FIELDS = [
  'tmTextUnitId',
  'repositoryName',
  'used',
  'targetLocale',
  'source',
  'target',
  'comment',
  'assetPath',
] as const;

const EXPORT_FIELD_PRIORITY = [
  'targetLocale',
  'source',
  'comment',
  'target',
  'repositoryName',
  'tmTextUnitId',
] as const;
const EXPORT_FIELD_PRIORITY_SET = new Set<WorkbenchExportFieldName>(EXPORT_FIELD_PRIORITY);

const DOUBLE_QUOTE = '"';
const COMMA = ',';
const CARRIAGE_RETURN = '\r';
const NEW_LINE = '\n';

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i += 1) {
    let c = i;
    for (let k = 0; k < 8; k += 1) {
      c = (c & 1) !== 0 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
})();

export type WorkbenchExportFormat = 'csv' | 'json';

export type WorkbenchExportFieldName =
  | 'assetId'
  | 'assetPath'
  | 'branchId'
  | 'comment'
  | 'createdDate'
  | 'doNotTranslate'
  | 'includedInLocalizedFile'
  | 'localeId'
  | 'name'
  | 'pluralForm'
  | 'pluralFormOther'
  | 'repositoryName'
  | 'source'
  | 'status'
  | 'target'
  | 'targetComment'
  | 'targetLocale'
  | 'tmTextUnitCreatedDate'
  | 'tmTextUnitId'
  | 'tmTextUnitVariantId'
  | 'used';

export const WORKBENCH_EXPORTABLE_FIELDS: Array<{
  name: WorkbenchExportFieldName;
  label: string;
}> = [
  { name: 'assetId', label: 'Asset ID' },
  { name: 'assetPath', label: 'Asset path' },
  { name: 'branchId', label: 'Branch ID' },
  { name: 'comment', label: 'Comment' },
  { name: 'createdDate', label: 'Created date' },
  { name: 'doNotTranslate', label: 'Do not translate' },
  { name: 'includedInLocalizedFile', label: 'Included in file' },
  { name: 'localeId', label: 'Locale ID' },
  { name: 'name', label: 'String ID' },
  { name: 'pluralForm', label: 'Plural form' },
  { name: 'pluralFormOther', label: 'Plural other' },
  { name: 'repositoryName', label: 'Repository' },
  { name: 'source', label: 'Source' },
  { name: 'status', label: 'Status' },
  { name: 'target', label: 'Target' },
  { name: 'targetComment', label: 'Target comment' },
  { name: 'targetLocale', label: 'Locale' },
  { name: 'tmTextUnitCreatedDate', label: 'Text unit created date' },
  { name: 'tmTextUnitId', label: 'TM text unit ID' },
  { name: 'tmTextUnitVariantId', label: 'TM text unit variant ID' },
  { name: 'used', label: 'Used' },
];

type CsvRecord = Record<string, unknown> & { __rowNumber?: number };

export function getOrderedExportFields(
  fields: readonly WorkbenchExportFieldName[],
): WorkbenchExportFieldName[] {
  return [
    ...EXPORT_FIELD_PRIORITY.filter((field) => fields.includes(field)),
    ...fields.filter((field) => !EXPORT_FIELD_PRIORITY_SET.has(field)),
  ];
}

export async function fetchAllTextUnitsForExport(
  request: TextUnitSearchRequest,
  totalLimit: number,
): Promise<ApiTextUnit[]> {
  const rows: ApiTextUnit[] = [];
  let offset = 0;

  while (rows.length < totalLimit) {
    const remaining = totalLimit - rows.length;
    const pageLimit = Math.min(MAX_EXPORT_BATCH_SIZE, remaining);
    const page = await searchTextUnits({
      ...request,
      offset,
      limit: pageLimit,
    });

    if (!page.length) {
      break;
    }

    rows.push(...page);
    if (page.length < pageLimit) {
      break;
    }

    offset += page.length;
  }

  return rows;
}

export function buildExportRows(
  textUnits: ApiTextUnit[],
  fields: readonly WorkbenchExportFieldName[],
): Array<Record<string, unknown>> {
  return textUnits.map((textUnit) =>
    Object.fromEntries(fields.map((field) => [field, extractExportValue(textUnit, field)])),
  );
}

export function buildExportBlob(
  rows: Array<Record<string, unknown>>,
  fields: readonly WorkbenchExportFieldName[],
  format: WorkbenchExportFormat,
): { blob: Blob; extension: WorkbenchExportFormat } {
  if (format === 'json') {
    return {
      blob: new Blob([JSON.stringify(rows, null, 2)], {
        type: 'application/json;charset=utf-8',
      }),
      extension: 'json',
    };
  }

  return {
    blob: new Blob([toCsv(rows, fields)], { type: 'text/csv;charset=utf-8' }),
    extension: 'csv',
  };
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

export function buildZipFile(files: Array<{ name: string; content: Uint8Array }>): Uint8Array {
  const encoder = new TextEncoder();
  const chunks: Uint8Array[] = [];
  const centralChunks: Uint8Array[] = [];
  let offset = 0;

  files.forEach((file) => {
    const nameBytes = encoder.encode(file.name);
    const content = file.content;
    const crc = crc32(content);

    const localHeader = new Uint8Array(30 + nameBytes.length);
    const localView = new DataView(localHeader.buffer);
    localView.setUint32(0, 0x04034b50, true);
    localView.setUint16(4, 20, true);
    localView.setUint16(6, 0, true);
    localView.setUint16(8, 0, true);
    localView.setUint16(10, 0, true);
    localView.setUint16(12, 0, true);
    localView.setUint32(14, crc, true);
    localView.setUint32(18, content.length, true);
    localView.setUint32(22, content.length, true);
    localView.setUint16(26, nameBytes.length, true);
    localView.setUint16(28, 0, true);
    localHeader.set(nameBytes, 30);
    chunks.push(localHeader, content);

    const centralHeader = new Uint8Array(46 + nameBytes.length);
    const centralView = new DataView(centralHeader.buffer);
    centralView.setUint32(0, 0x02014b50, true);
    centralView.setUint16(4, 20, true);
    centralView.setUint16(6, 20, true);
    centralView.setUint16(8, 0, true);
    centralView.setUint16(10, 0, true);
    centralView.setUint16(12, 0, true);
    centralView.setUint16(14, 0, true);
    centralView.setUint32(16, crc, true);
    centralView.setUint32(20, content.length, true);
    centralView.setUint32(24, content.length, true);
    centralView.setUint16(28, nameBytes.length, true);
    centralView.setUint16(30, 0, true);
    centralView.setUint16(32, 0, true);
    centralView.setUint16(34, 0, true);
    centralView.setUint16(36, 0, true);
    centralView.setUint32(38, 0, true);
    centralView.setUint32(42, offset, true);
    centralHeader.set(nameBytes, 46);
    centralChunks.push(centralHeader);

    offset += localHeader.length + content.length;
  });

  const centralDirectorySize = centralChunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const endOfCentralDirectory = new Uint8Array(22);
  const endView = new DataView(endOfCentralDirectory.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(4, 0, true);
  endView.setUint16(6, 0, true);
  endView.setUint16(8, files.length, true);
  endView.setUint16(10, files.length, true);
  endView.setUint32(12, centralDirectorySize, true);
  endView.setUint32(16, offset, true);
  endView.setUint16(20, 0, true);

  const totalSize = offset + centralDirectorySize + endOfCentralDirectory.length;
  const zipData = new Uint8Array(totalSize);
  let position = 0;

  chunks.forEach((chunk) => {
    zipData.set(chunk, position);
    position += chunk.length;
  });
  centralChunks.forEach((chunk) => {
    zipData.set(chunk, position);
    position += chunk.length;
  });
  zipData.set(endOfCentralDirectory, position);

  return zipData;
}

export function parseImportFileContent(
  fileName: string,
  content: string,
): { format: WorkbenchExportFormat; textUnits: ImportTextUnitBatchRow[]; errors: string[] } {
  const trimmed = content.trim();
  if (!trimmed) {
    throw new Error('The selected file is empty.');
  }

  const lowerName = fileName.toLowerCase();
  const format: WorkbenchExportFormat =
    lowerName.endsWith('.json') || trimmed.startsWith('[') || trimmed.startsWith('{')
      ? 'json'
      : 'csv';

  const records = format === 'json' ? parseJsonRecords(trimmed) : csvToObjects(trimmed);
  const { textUnits, errors } = normalizeImportRecords(records);
  return { format, textUnits, errors };
}

export function buildImportTemplateCsv(): string {
  return ['repositoryName', 'assetPath', 'targetLocale', 'tmTextUnitId', 'name', 'target'].join(
    ',',
  );
}

function extractExportValue(textUnit: ApiTextUnit, field: WorkbenchExportFieldName): unknown {
  switch (field) {
    case 'assetId':
      return textUnit.assetId ?? null;
    case 'assetPath':
      return textUnit.assetPath ?? null;
    case 'branchId':
      return textUnit.branchId ?? null;
    case 'comment':
      return textUnit.comment ?? null;
    case 'createdDate':
      return textUnit.createdDate ?? null;
    case 'doNotTranslate':
      return textUnit.doNotTranslate ?? null;
    case 'includedInLocalizedFile':
      return textUnit.includedInLocalizedFile ?? null;
    case 'localeId':
      return textUnit.localeId ?? null;
    case 'name':
      return textUnit.name;
    case 'pluralForm':
      return textUnit.pluralForm ?? null;
    case 'pluralFormOther':
      return textUnit.pluralFormOther ?? null;
    case 'repositoryName':
      return textUnit.repositoryName ?? null;
    case 'source':
      return textUnit.source ?? null;
    case 'status':
      return textUnit.status ?? null;
    case 'target':
      return textUnit.target ?? null;
    case 'targetComment':
      return textUnit.targetComment ?? null;
    case 'targetLocale':
      return textUnit.targetLocale;
    case 'tmTextUnitCreatedDate':
      return textUnit.tmTextUnitCreatedDate ?? null;
    case 'tmTextUnitId':
      return textUnit.tmTextUnitId;
    case 'tmTextUnitVariantId':
      return textUnit.tmTextUnitVariantId ?? null;
    case 'used':
      return textUnit.used;
  }
}

function escapeCsvCell(value: unknown): string {
  if (value == null) {
    return '';
  }
  const stringValue = toScalarString(value);
  if (stringValue == null) {
    return '';
  }
  if (
    stringValue.includes(DOUBLE_QUOTE) ||
    stringValue.includes(COMMA) ||
    stringValue.includes(NEW_LINE) ||
    stringValue.includes(CARRIAGE_RETURN)
  ) {
    return `"${stringValue.replace(/"/g, '""')}"`;
  }
  return stringValue;
}

function toCsv(rows: Array<Record<string, unknown>>, fields: readonly WorkbenchExportFieldName[]) {
  const header = fields.join(',');
  const lines = rows.map((row) => fields.map((field) => escapeCsvCell(row[field])).join(','));
  return [header, ...lines].join('\n');
}

function crc32(data: Uint8Array) {
  let crc = 0 ^ -1;
  for (let i = 0; i < data.length; i += 1) {
    crc = CRC_TABLE[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ -1) >>> 0;
}

function parseJsonRecords(content: string): CsvRecord[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(content);
  } catch {
    throw new Error('The JSON file could not be parsed.');
  }

  const records = Array.isArray(parsed)
    ? parsed
    : parsed &&
        typeof parsed === 'object' &&
        Array.isArray((parsed as { textUnits?: unknown }).textUnits)
      ? (parsed as { textUnits: unknown[] }).textUnits
      : null;

  if (!records) {
    throw new Error('JSON import must be an array or an object with a textUnits array.');
  }

  return records.map((record, index) => ({
    ...(record && typeof record === 'object' ? (record as Record<string, unknown>) : {}),
    __rowNumber: index + 1,
  }));
}

function stripBom(content: string): string {
  return content.charCodeAt(0) === 0xfeff ? content.slice(1) : content;
}

function parseCsv(content: string): string[][] {
  const rows: string[][] = [];
  const sanitized = stripBom(content);
  let current = '';
  let row: string[] = [];
  let inQuotes = false;

  for (let i = 0; i < sanitized.length; i += 1) {
    const char = sanitized[i];

    if (char === DOUBLE_QUOTE) {
      if (inQuotes && sanitized[i + 1] === DOUBLE_QUOTE) {
        current += DOUBLE_QUOTE;
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
      continue;
    }

    if (char === COMMA && !inQuotes) {
      row.push(current);
      current = '';
      continue;
    }

    if ((char === NEW_LINE || char === CARRIAGE_RETURN) && !inQuotes) {
      if (char === CARRIAGE_RETURN && sanitized[i + 1] === NEW_LINE) {
        i += 1;
      }
      row.push(current);
      rows.push(row);
      row = [];
      current = '';
      continue;
    }

    current += char;
  }

  if (current !== '' || row.length > 0) {
    row.push(current);
    rows.push(row);
  }

  return rows;
}

function csvToObjects(content: string): CsvRecord[] {
  const rows = parseCsv(content);
  if (!rows.length) {
    return [];
  }

  const headers = rows[0].map((header) => (header != null ? header.trim() : ''));
  return rows
    .slice(1)
    .map((row, index) => {
      const record: CsvRecord = { __rowNumber: index + 2 };
      headers.forEach((header, columnIndex) => {
        if (!header) {
          return;
        }
        record[header] = row[columnIndex] ?? '';
      });
      return record;
    })
    .filter((row) =>
      Object.entries(row).some(
        ([key, value]) => key !== '__rowNumber' && (toScalarString(value)?.trim().length ?? 0) > 0,
      ),
    );
}

function normalizeImportRecords(records: CsvRecord[]): {
  textUnits: ImportTextUnitBatchRow[];
  errors: string[];
} {
  const textUnits: ImportTextUnitBatchRow[] = [];
  const errors: string[] = [];

  records.forEach((record, index) => {
    const rowNumber = record.__rowNumber ?? index + 1;
    const repositoryName = toNonEmptyString(record.repositoryName);
    if (!repositoryName) {
      errors.push(`Row ${rowNumber}: repositoryName is required.`);
      return;
    }

    const assetPath = toNonEmptyString(record.assetPath);
    if (!assetPath) {
      errors.push(`Row ${rowNumber}: assetPath is required.`);
      return;
    }

    const targetLocale = toNonEmptyString(record.targetLocale ?? record.locale);
    if (!targetLocale) {
      errors.push(`Row ${rowNumber}: targetLocale is required.`);
      return;
    }

    const targetValue = toScalarString(record.target);
    if (record.target == null || targetValue == null) {
      errors.push(`Row ${rowNumber}: target is required.`);
      return;
    }

    const tmTextUnitId = parseInteger(record.tmTextUnitId);
    const name = toNonEmptyString(record.name);
    if (tmTextUnitId === undefined) {
      errors.push(`Row ${rowNumber}: tmTextUnitId must be an integer.`);
      return;
    }
    if (tmTextUnitId === null && !name) {
      errors.push(`Row ${rowNumber}: either tmTextUnitId or name is required.`);
      return;
    }

    const branchId = parseOptionalInteger(record.branchId, rowNumber, 'branchId', errors);
    const tmTextUnitVariantId = parseOptionalInteger(
      record.tmTextUnitVariantId,
      rowNumber,
      'tmTextUnitVariantId',
      errors,
    );
    const localeId = parseOptionalInteger(record.localeId, rowNumber, 'localeId', errors);
    const assetId = parseOptionalInteger(record.assetId, rowNumber, 'assetId', errors);
    if ([branchId, tmTextUnitVariantId, localeId, assetId].includes(undefined)) {
      return;
    }

    const includedInLocalizedFile = parseBoolean(record.includedInLocalizedFile);
    if (includedInLocalizedFile === undefined && isValueProvided(record.includedInLocalizedFile)) {
      errors.push(`Row ${rowNumber}: includedInLocalizedFile must be true or false.`);
      return;
    }

    const doNotTranslate = parseBoolean(record.doNotTranslate);
    if (doNotTranslate === undefined && isValueProvided(record.doNotTranslate)) {
      errors.push(`Row ${rowNumber}: doNotTranslate must be true or false.`);
      return;
    }

    const textUnit: ImportTextUnitBatchRow = {
      repositoryName,
      assetPath,
      targetLocale,
      target: targetValue,
    };

    if (tmTextUnitId !== null) {
      textUnit.tmTextUnitId = tmTextUnitId;
    }
    if (name) {
      textUnit.name = name;
    }

    const comment = toOptionalString(record.comment);
    if (comment !== undefined) {
      textUnit.comment = comment;
    }
    const targetComment = toOptionalString(record.targetComment);
    if (targetComment !== undefined) {
      textUnit.targetComment = targetComment;
    }
    const status = toOptionalString(record.status);
    if (status !== undefined) {
      textUnit.status = status.toUpperCase();
    }
    if (typeof includedInLocalizedFile === 'boolean') {
      textUnit.includedInLocalizedFile = includedInLocalizedFile;
    }
    if (typeof doNotTranslate === 'boolean') {
      textUnit.doNotTranslate = doNotTranslate;
    }

    const pluralForm = toOptionalString(record.pluralForm);
    if (pluralForm !== undefined) {
      textUnit.pluralForm = pluralForm;
    }
    const pluralFormOther = toOptionalString(record.pluralFormOther);
    if (pluralFormOther !== undefined) {
      textUnit.pluralFormOther = pluralFormOther;
    }
    if (branchId !== null) {
      textUnit.branchId = branchId;
    }
    if (tmTextUnitVariantId !== null) {
      textUnit.tmTextUnitVariantId = tmTextUnitVariantId;
    }
    if (localeId !== null) {
      textUnit.localeId = localeId;
    }
    if (assetId !== null) {
      textUnit.assetId = assetId;
    }

    const createdDate = toOptionalString(record.createdDate);
    if (createdDate !== undefined) {
      textUnit.createdDate = createdDate;
    }
    const tmTextUnitCreatedDate = toOptionalString(record.tmTextUnitCreatedDate);
    if (tmTextUnitCreatedDate !== undefined) {
      textUnit.tmTextUnitCreatedDate = tmTextUnitCreatedDate;
    }

    textUnits.push(textUnit);
  });

  return { textUnits, errors };
}

function toNonEmptyString(value: unknown): string | null {
  const stringValue = toScalarString(value);
  if (stringValue == null) {
    return null;
  }
  const trimmed = stringValue.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function toOptionalString(value: unknown): string | undefined {
  return toScalarString(value);
}

function parseInteger(value: unknown): number | null | undefined {
  if (value == null || value === '') {
    return null;
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : undefined;
  }
  const stringValue = toScalarString(value);
  if (stringValue == null) {
    return undefined;
  }
  const parsed = Number.parseInt(stringValue.trim(), 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}

function parseOptionalInteger(
  value: unknown,
  rowNumber: number,
  fieldName: string,
  errors: string[],
): number | null | undefined {
  const parsed = parseInteger(value);
  if (parsed === undefined) {
    errors.push(`Row ${rowNumber}: ${fieldName} must be an integer.`);
  }
  return parsed;
}

function parseBoolean(value: unknown): boolean | undefined {
  if (value == null || value === '') {
    return undefined;
  }
  if (typeof value === 'boolean') {
    return value;
  }
  const stringValue = toScalarString(value);
  if (stringValue == null) {
    return undefined;
  }
  const normalized = stringValue.trim().toLowerCase();
  if (normalized === 'true' || normalized === '1') {
    return true;
  }
  if (normalized === 'false' || normalized === '0') {
    return false;
  }
  return undefined;
}

function isValueProvided(value: unknown): boolean {
  if (value == null) {
    return false;
  }
  if (typeof value === 'string') {
    return value.trim().length > 0;
  }
  return toScalarString(value) != null;
}

function toScalarString(value: unknown): string | undefined {
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  return undefined;
}
