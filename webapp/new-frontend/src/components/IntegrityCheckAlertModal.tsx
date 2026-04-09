import { useCallback, useEffect, useState } from 'react';

import { Modal } from './Modal';

type Props = {
  open: boolean;
  title: string;
  body: string;
  failureDetail?: string | null;
  reportMessage?: string | null;
  reportHtml?: string | null;
  primaryLabel: string;
  onPrimary: () => void;
  secondaryLabel?: string;
  onSecondary?: () => void;
  primaryVariant?: 'danger' | 'primary';
  onClose?: () => void;
};

export function IntegrityCheckAlertModal({
  open,
  title,
  body,
  failureDetail,
  reportMessage,
  reportHtml,
  primaryLabel,
  onPrimary,
  secondaryLabel,
  onSecondary,
  primaryVariant = 'primary',
  onClose,
}: Props) {
  const [copyStatus, setCopyStatus] = useState<'idle' | 'copied' | 'failed'>('idle');

  useEffect(() => {
    setCopyStatus('idle');
  }, [open, reportMessage]);

  const onCopyReportMessage = useCallback(async () => {
    if (!reportMessage) {
      return;
    }
    try {
      if (reportHtml && typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
        await navigator.clipboard.write([
          new ClipboardItem({
            'text/html': new Blob([reportHtml], { type: 'text/html' }),
            'text/plain': new Blob([reportMessage], { type: 'text/plain' }),
          }),
        ]);
      } else {
        await navigator.clipboard.writeText(reportMessage);
      }
      setCopyStatus('copied');
    } catch {
      try {
        await navigator.clipboard.writeText(reportMessage);
        setCopyStatus('copied');
      } catch {
        setCopyStatus('failed');
      }
    }
  }, [reportHtml, reportMessage]);

  return (
    <Modal
      open={open}
      size={reportMessage ? 'xl' : 'md'}
      role="alertdialog"
      ariaLabel={title}
      onClose={onClose}
      closeOnBackdrop={Boolean(onClose)}
      className={`integrity-check-alert-modal${
        reportMessage ? ' integrity-check-alert-modal--report' : ''
      }`}
    >
      <div className="modal__header integrity-check-alert-modal__header">
        <div className="modal__title">{title}</div>
      </div>
      {reportMessage ? (
        <div className="integrity-check-alert-modal__body">
          <div className="integrity-check-alert-modal__error" role="alert">
            {body}
          </div>
          {failureDetail ? (
            <section className="integrity-check-alert-modal__section">
              <pre className="integrity-check-alert-modal__pre">{failureDetail}</pre>
            </section>
          ) : null}
          <section className="integrity-check-alert-modal__section integrity-check-alert-modal__section--report">
            <div className="integrity-check-alert-modal__section-header">
              <p className="integrity-check-alert-modal__section-title">
                If you think there is an issue with the check, copy-paste this error report.
              </p>
              <div className="integrity-check-alert-modal__copy-group">
                {copyStatus === 'copied' ? (
                  <div className="integrity-check-alert-modal__copy-status">Copied.</div>
                ) : null}
                {copyStatus === 'failed' ? (
                  <div className="integrity-check-alert-modal__copy-status integrity-check-alert-modal__copy-status--error">
                    Copy failed.
                  </div>
                ) : null}
                <button
                  type="button"
                  className="integrity-check-alert-modal__copy-button"
                  onClick={() => {
                    void onCopyReportMessage();
                  }}
                  aria-label="Copy error report"
                  title="Copy error report"
                >
                  <svg
                    className="integrity-check-alert-modal__copy-icon"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                    focusable="false"
                  >
                    <rect x="8" y="8" width="11" height="13" rx="2" fill="none" />
                    <path d="M5 16V5a2 2 0 0 1 2-2h9" fill="none" />
                  </svg>
                </button>
              </div>
            </div>
            {reportHtml ? (
              <div
                className="integrity-check-alert-modal__report"
                dangerouslySetInnerHTML={{ __html: reportHtml }}
              />
            ) : (
              <pre className="integrity-check-alert-modal__report-fallback">{reportMessage}</pre>
            )}
          </section>
        </div>
      ) : (
        <div className="modal__body">{body}</div>
      )}
      <div className="modal__actions">
        {secondaryLabel && onSecondary ? (
          <button type="button" className="modal__button" onClick={onSecondary}>
            {secondaryLabel}
          </button>
        ) : null}
        <button
          type="button"
          className={`modal__button modal__button--${primaryVariant}`}
          onClick={onPrimary}
        >
          {primaryLabel}
        </button>
      </div>
    </Modal>
  );
}
