import { useEffect, useRef, useState } from 'react';

import { importTextUnitsBatch } from '../../api/text-units';
import { Modal } from '../../components/Modal';
import {
  buildImportTemplateCsv,
  parseImportFileContent,
  type WorkbenchExportFormat,
} from './workbench-import-export';

type Props = {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
};

export function WorkbenchImportModal({ open, onClose, onImported }: Props) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [fileName, setFileName] = useState('');
  const [format, setFormat] = useState<WorkbenchExportFormat | null>(null);
  const [readyCount, setReadyCount] = useState(0);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [textUnits, setTextUnits] = useState<
    ReturnType<typeof parseImportFileContent>['textUnits']
  >([]);
  const [fileError, setFileError] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [isImporting, setIsImporting] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }

    setFileName('');
    setFormat(null);
    setReadyCount(0);
    setValidationErrors([]);
    setTextUnits([]);
    setFileError(null);
    setErrorMessage(null);
    setIsDragging(false);
    setIsImporting(false);

    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }, [open]);

  const isReadyForImport = textUnits.length > 0 && validationErrors.length === 0 && !fileError;

  const handleRequestClose = () => {
    if (!isImporting) {
      onClose();
    }
  };

  const loadFile = async (file: File) => {
    const extension = file.name.toLowerCase().split('.').pop();
    if (!extension || !['csv', 'json'].includes(extension)) {
      setFileName(file.name);
      setFormat(null);
      setReadyCount(0);
      setValidationErrors([]);
      setTextUnits([]);
      setFileError('Select a CSV or JSON file.');
      setErrorMessage(null);
      setIsDragging(false);
      return;
    }

    try {
      const content = await file.text();
      const parsed = parseImportFileContent(file.name, content);
      setFileName(file.name);
      setFormat(parsed.format);
      setReadyCount(parsed.textUnits.length);
      setValidationErrors(parsed.errors);
      setTextUnits(parsed.textUnits);
      setFileError(null);
      setErrorMessage(null);
    } catch (error) {
      setFileName(file.name);
      setFormat(null);
      setReadyCount(0);
      setValidationErrors([]);
      setTextUnits([]);
      setFileError(error instanceof Error ? error.message : 'Could not read the file.');
    }
  };

  const handleImport = async () => {
    if (!isReadyForImport) {
      return;
    }

    setIsImporting(true);
    setErrorMessage(null);

    try {
      await importTextUnitsBatch({ textUnits });
      onImported();
      onClose();
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'Import failed.');
    } finally {
      setIsImporting(false);
    }
  };

  const downloadTemplate = () => {
    const blob = new Blob([`${buildImportTemplateCsv()}\n`], {
      type: 'text/csv;charset=utf-8',
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'workbench-import-template.csv';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  return (
    <Modal
      open={open}
      size="md"
      ariaLabel="Import search results"
      onClose={handleRequestClose}
      closeOnBackdrop={!isImporting}
    >
      <div className="modal__header">
        <div className="modal__title">Import search results</div>
      </div>
      <div className="modal__body workbench-importexport">
        <p className="workbench-importexport__intro">
          Upload CSV or JSON rows to batch import translations into Mojito.
        </p>
        <ul className="workbench-importexport__help">
          <li>Required columns: repositoryName, assetPath, targetLocale, target.</li>
          <li>Provide either tmTextUnitId or name for each row.</li>
        </ul>
        <button
          type="button"
          className="workbench-worksetbar__button"
          onClick={downloadTemplate}
          disabled={isImporting}
        >
          Download template
        </button>

        <input
          ref={fileInputRef}
          type="file"
          accept=".csv,.json,text/csv,application/json"
          hidden
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              void loadFile(file);
            }
          }}
          disabled={isImporting}
        />

        <div
          className={`workbench-importexport__dropzone${isDragging ? ' is-active' : ''}${
            isImporting ? ' is-disabled' : ''
          }`}
          role="button"
          tabIndex={0}
          onClick={() => fileInputRef.current?.click()}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              fileInputRef.current?.click();
            }
          }}
          onDragOver={(event) => {
            event.preventDefault();
            if (!isImporting) {
              setIsDragging(true);
            }
          }}
          onDragLeave={(event) => {
            event.preventDefault();
            setIsDragging(false);
          }}
          onDrop={(event) => {
            event.preventDefault();
            setIsDragging(false);
            const file = event.dataTransfer.files?.[0];
            if (file && !isImporting) {
              void loadFile(file);
            }
          }}
        >
          {fileName ? (
            <span>Selected file: {fileName}</span>
          ) : (
            <span>Drop a CSV or JSON file here, or click to browse.</span>
          )}
        </div>

        {fileError ? <div className="alert alert--error">{fileError}</div> : null}

        {fileName ? (
          <div className="workbench-importexport__summary">
            <strong>{fileName}</strong>
            <span>{format ? format.toUpperCase() : 'N/A'}</span>
            <span>Ready rows: {readyCount}</span>
            <span>Skipped rows: {validationErrors.length}</span>
          </div>
        ) : null}

        {validationErrors.length > 0 ? (
          <div className="alert alert--warning">
            <div className="workbench-importexport__alert-title">Validation issues</div>
            <ul className="workbench-importexport__errors">
              {validationErrors.map((error) => (
                <li key={error}>{error}</li>
              ))}
            </ul>
          </div>
        ) : null}

        {errorMessage ? <div className="alert alert--error">{errorMessage}</div> : null}
      </div>
      <div className="modal__actions">
        <button
          type="button"
          className="modal__button"
          onClick={handleRequestClose}
          disabled={isImporting}
        >
          Cancel
        </button>
        <button
          type="button"
          className="modal__button modal__button--primary"
          onClick={() => {
            void handleImport();
          }}
          disabled={!isReadyForImport || isImporting}
        >
          {isImporting ? 'Importing…' : 'Import'}
        </button>
      </div>
    </Modal>
  );
}
