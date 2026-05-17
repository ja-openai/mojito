import { useEffect, useMemo, useState } from 'react';

import type { ApiRepository } from '../../api/repositories';
import type { TextUnitSearchRequest } from '../../api/text-units';
import { Modal } from '../../components/Modal';
import { getNonRootRepositoryLocaleTags } from '../../utils/repositoryLocales';
import {
  buildExportBlob,
  buildExportRows,
  buildZipFile,
  DEFAULT_EXPORT_FIELDS,
  DEFAULT_EXPORT_LIMIT,
  downloadBlob,
  fetchAllTextUnitsForExport,
  getOrderedExportFields,
  WORKBENCH_EXPORTABLE_FIELDS,
  type WorkbenchExportFieldName,
  type WorkbenchExportFormat,
} from './workbench-import-export';

type Props = {
  open: boolean;
  onClose: () => void;
  activeSearchRequest: TextUnitSearchRequest | null;
  repositories: ApiRepository[];
};

export function WorkbenchExportModal({ open, onClose, activeSearchRequest, repositories }: Props) {
  const [selectedFields, setSelectedFields] = useState<WorkbenchExportFieldName[]>(
    Array.from(DEFAULT_EXPORT_FIELDS),
  );
  const [limitInput, setLimitInput] = useState(String(DEFAULT_EXPORT_LIMIT));
  const [format, setFormat] = useState<WorkbenchExportFormat>('csv');
  const [splitByLocale, setSplitByLocale] = useState(false);
  const [selectedLocales, setSelectedLocales] = useState<string[]>([]);
  const [isExporting, setIsExporting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const availableLocales = useMemo(() => {
    if (!activeSearchRequest) {
      return [];
    }

    const repositoryIds = new Set(activeSearchRequest.repositoryIds);
    const localeSet = new Set<string>();

    repositories.forEach((repository) => {
      if (!repositoryIds.has(repository.id)) {
        return;
      }
      getNonRootRepositoryLocaleTags(repository).forEach((locale) => localeSet.add(locale));
    });

    if (localeSet.size === 0) {
      activeSearchRequest.localeTags.forEach((locale) => localeSet.add(locale));
    }

    return Array.from(localeSet).sort();
  }, [activeSearchRequest, repositories]);

  useEffect(() => {
    if (!open) {
      return;
    }

    setSelectedFields(Array.from(DEFAULT_EXPORT_FIELDS));
    setLimitInput(String(DEFAULT_EXPORT_LIMIT));
    setFormat('csv');
    setSplitByLocale(false);
    setSelectedLocales(
      activeSearchRequest?.localeTags?.length
        ? activeSearchRequest.localeTags.filter((locale) => availableLocales.includes(locale))
        : availableLocales.slice(),
    );
    setIsExporting(false);
    setErrorMessage(null);
  }, [activeSearchRequest, availableLocales, open]);

  const toggleField = (field: WorkbenchExportFieldName) => {
    setSelectedFields((current) =>
      current.includes(field) ? current.filter((value) => value !== field) : [...current, field],
    );
  };

  const handleRequestClose = () => {
    if (!isExporting) {
      onClose();
    }
  };

  const handleExport = async () => {
    if (!activeSearchRequest) {
      setErrorMessage('Search results are not ready yet.');
      return;
    }

    if (selectedFields.length === 0) {
      setErrorMessage('Select at least one field to export.');
      return;
    }

    const parsedLimit = Number.parseInt(limitInput, 10);
    if (!Number.isFinite(parsedLimit) || parsedLimit <= 0) {
      setErrorMessage('Enter a positive export limit.');
      return;
    }

    if (splitByLocale && selectedLocales.length === 0) {
      setErrorMessage('Select at least one locale for split export.');
      return;
    }

    setIsExporting(true);
    setErrorMessage(null);

    try {
      const orderedFields = getOrderedExportFields(selectedFields);

      if (splitByLocale) {
        const files: Array<{ name: string; content: Uint8Array }> = [];
        const skippedLocales: string[] = [];
        const timestamp = Date.now();

        for (const locale of selectedLocales) {
          const rows = await fetchAllTextUnitsForExport(
            { ...activeSearchRequest, localeTags: [locale] },
            parsedLimit,
          );
          if (rows.length === 0) {
            skippedLocales.push(locale);
            continue;
          }
          const exportRows = buildExportRows(rows, orderedFields);
          const payload = buildExportBlob(exportRows, orderedFields, format);
          const safeLocale = locale.replace(/[^A-Za-z0-9._-]/g, '_');
          files.push({
            name: `workbench-export-${safeLocale}-${timestamp}.${payload.extension}`,
            content: new Uint8Array(await payload.blob.arrayBuffer()),
          });
        }

        if (files.length === 0) {
          setErrorMessage('No results found for the selected locales.');
          return;
        }

        const zipBytes = buildZipFile(files);
        downloadBlob(
          new Blob([zipBytes], { type: 'application/zip' }),
          `workbench-export-locales-${timestamp}.zip`,
        );

        if (skippedLocales.length > 0) {
          setErrorMessage(`Skipped locales with no results: ${skippedLocales.join(', ')}`);
          return;
        }
      } else {
        const rows = await fetchAllTextUnitsForExport(activeSearchRequest, parsedLimit);
        const exportRows = buildExportRows(rows, orderedFields);
        const payload = buildExportBlob(exportRows, orderedFields, format);
        downloadBlob(payload.blob, `workbench-export-${Date.now()}.${payload.extension}`);
      }

      onClose();
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'Export failed.');
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <Modal
      open={open}
      size="lg"
      ariaLabel="Export search results"
      onClose={handleRequestClose}
      closeOnBackdrop={!isExporting}
    >
      <div className="modal__header">
        <div className="modal__title">Export search results</div>
      </div>
      <div className="modal__body workbench-importexport">
        <p className="workbench-importexport__intro">
          Export the current workbench search results to CSV or JSON.
        </p>

        <label className="workbench-importexport__field">
          <span className="workbench-importexport__label">Format</span>
          <select
            className="input workbench-importexport__input"
            value={format}
            onChange={(event) => setFormat(event.target.value as WorkbenchExportFormat)}
            disabled={isExporting}
          >
            <option value="csv">CSV</option>
            <option value="json">JSON</option>
          </select>
        </label>

        <label className="workbench-importexport__field">
          <span className="workbench-importexport__label">Max rows</span>
          <input
            className="input workbench-importexport__input"
            type="number"
            min={1}
            step={1}
            value={limitInput}
            onChange={(event) => setLimitInput(event.target.value)}
            disabled={isExporting}
          />
        </label>

        <label className="workbench-importexport__checkbox">
          <input
            type="checkbox"
            checked={splitByLocale}
            onChange={(event) => {
              const nextValue = event.target.checked;
              setSplitByLocale(nextValue);
              if (nextValue && selectedLocales.length === 0) {
                setSelectedLocales(availableLocales);
              }
            }}
            disabled={isExporting || availableLocales.length === 0}
          />
          <span>Split files by locale</span>
        </label>

        {splitByLocale ? (
          <label className="workbench-importexport__field">
            <span className="workbench-importexport__label">Locales</span>
            <select
              className="input workbench-importexport__input workbench-importexport__input--multi"
              multiple
              value={selectedLocales}
              onChange={(event) =>
                setSelectedLocales(
                  Array.from(event.target.selectedOptions, (option) => option.value),
                )
              }
              disabled={isExporting}
            >
              {availableLocales.map((locale) => (
                <option key={locale} value={locale}>
                  {locale}
                </option>
              ))}
            </select>
          </label>
        ) : null}

        <div className="workbench-importexport__field">
          <div className="workbench-importexport__label">Fields</div>
          <div className="workbench-importexport__grid">
            {WORKBENCH_EXPORTABLE_FIELDS.map((field) => (
              <label key={field.name} className="workbench-importexport__checkbox">
                <input
                  type="checkbox"
                  checked={selectedFields.includes(field.name)}
                  onChange={() => toggleField(field.name)}
                  disabled={isExporting}
                />
                <span>{field.label}</span>
              </label>
            ))}
          </div>
        </div>

        {errorMessage ? <div className="alert alert--error">{errorMessage}</div> : null}
      </div>
      <div className="modal__actions">
        <button
          type="button"
          className="modal__button"
          onClick={handleRequestClose}
          disabled={isExporting}
        >
          Cancel
        </button>
        <button
          type="button"
          className="modal__button modal__button--primary"
          onClick={() => {
            void handleExport();
          }}
          disabled={isExporting || !activeSearchRequest}
        >
          {isExporting ? 'Exporting…' : 'Export'}
        </button>
      </div>
    </Modal>
  );
}
