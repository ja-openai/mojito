import './json-payload-modal.css';

import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { formatJsonPayload, getJsonPayloadInstructions } from '../utils/jsonPayloadFormatting';
import { MarkdownPreview } from './markdown/MarkdownPreview';
import { Modal } from './Modal';

export type JsonPayloadModalItem = {
  key: string;
  label: string;
  title: string;
  url: string;
};

type JsonPayloadModalProps = {
  open: boolean;
  items: JsonPayloadModalItem[];
  activeItemKey?: string | null;
  onActiveItemKeyChange: (itemKey: string) => void;
  onClose: () => void;
};

type PayloadView = 'json' | 'instructions';

export function JsonPayloadModal({
  open,
  items,
  activeItemKey,
  onActiveItemKeyChange,
  onClose,
}: JsonPayloadModalProps) {
  const [copyStatus, setCopyStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
  const [payloadView, setPayloadView] = useState<PayloadView>('json');
  const activeItem = useMemo(
    () => items.find((item) => item.key === activeItemKey) ?? items[0] ?? null,
    [activeItemKey, items],
  );
  const activeUrl = activeItem?.url ?? null;

  const payloadQuery = useQuery({
    queryKey: ['json-payload-modal', activeUrl],
    queryFn: () => fetchPayload(activeUrl),
    enabled: open && Boolean(activeUrl),
    staleTime: 60_000,
  });

  const formattedPayload = useMemo(() => {
    if (!payloadQuery.data) {
      return '';
    }
    return formatJsonPayload(payloadQuery.data);
  }, [payloadQuery.data]);
  const instructions = useMemo(() => {
    if (!payloadQuery.data) {
      return null;
    }
    return getJsonPayloadInstructions(payloadQuery.data);
  }, [payloadQuery.data]);
  const activePayloadView =
    payloadView === 'instructions' && instructions ? 'instructions' : 'json';
  const displayedContent =
    activePayloadView === 'instructions' ? (instructions ?? '') : formattedPayload;

  useEffect(() => {
    setPayloadView('json');
  }, [activeUrl, open]);

  useEffect(() => {
    setCopyStatus('idle');
  }, [activePayloadView, activeUrl, open]);

  const copyPayload = async () => {
    if (!displayedContent) {
      return;
    }
    try {
      await navigator.clipboard.writeText(displayedContent);
      setCopyStatus('copied');
    } catch {
      setCopyStatus('failed');
    }
  };

  return (
    <Modal
      open={open}
      size="xl"
      ariaLabel={activeItem?.title ?? 'JSON payload'}
      onClose={onClose}
      closeOnBackdrop
      className="json-payload-modal"
    >
      <div className="modal__header json-payload-modal__header">
        <div className="modal__title">{activeItem?.title ?? 'JSON payload'}</div>
      </div>
      {items.length > 1 ? (
        <div className="json-payload-modal__tabs" role="tablist" aria-label="JSON payloads">
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`json-payload-modal__tab${
                item.key === activeItem?.key ? ' json-payload-modal__tab--active' : ''
              }`}
              role="tab"
              aria-selected={item.key === activeItem?.key}
              onClick={() => onActiveItemKeyChange(item.key)}
            >
              {item.label}
            </button>
          ))}
        </div>
      ) : null}
      {instructions ? (
        <div className="json-payload-modal__view-tabs" role="tablist" aria-label="Payload view">
          <button
            type="button"
            className={`json-payload-modal__view-tab${
              activePayloadView === 'json' ? ' json-payload-modal__view-tab--active' : ''
            }`}
            role="tab"
            aria-selected={activePayloadView === 'json'}
            onClick={() => setPayloadView('json')}
          >
            JSON
          </button>
          <button
            type="button"
            className={`json-payload-modal__view-tab${
              activePayloadView === 'instructions' ? ' json-payload-modal__view-tab--active' : ''
            }`}
            role="tab"
            aria-selected={activePayloadView === 'instructions'}
            onClick={() => setPayloadView('instructions')}
          >
            Instructions
          </button>
        </div>
      ) : null}
      <div className="modal__body json-payload-modal__body">
        {!activeItem ? (
          <div className="json-payload-modal__state">No JSON payload available.</div>
        ) : null}
        {activeItem && payloadQuery.isLoading ? (
          <div className="json-payload-modal__state">
            <span className="spinner spinner--md" aria-hidden />
            <span>Loading JSON…</span>
          </div>
        ) : null}
        {activeItem && payloadQuery.error ? (
          <div className="json-payload-modal__state json-payload-modal__state--error">
            {payloadQuery.error instanceof Error
              ? payloadQuery.error.message
              : 'Failed to load JSON payload.'}
          </div>
        ) : null}
        {formattedPayload && activePayloadView === 'json' ? (
          <pre className="json-payload-modal__pre">{formattedPayload}</pre>
        ) : null}
        {instructions && activePayloadView === 'instructions' ? (
          <MarkdownPreview
            markdown={instructions}
            className="json-payload-modal__instructions"
            emptyLabel="No instructions provided."
          />
        ) : null}
      </div>
      <div className="modal__actions json-payload-modal__actions">
        {copyStatus !== 'idle' ? (
          <div className="json-payload-modal__copy-status" aria-live="polite">
            {copyStatus === 'copied' ? 'Copied.' : null}
            {copyStatus === 'failed' ? 'Copy failed.' : null}
          </div>
        ) : null}
        <div className="json-payload-modal__button-group">
          {activeUrl ? (
            <a
              className="modal__button json-payload-modal__raw-link"
              href={activeUrl}
              target="_blank"
              rel="noreferrer"
            >
              Open raw
            </a>
          ) : null}
          <button
            type="button"
            className="modal__button"
            onClick={() => {
              void copyPayload();
            }}
            disabled={!displayedContent}
          >
            {copyStatus === 'copied' ? 'Copied' : 'Copy'}
          </button>
          <button type="button" className="modal__button modal__button--primary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </Modal>
  );
}

async function fetchPayload(url: string): Promise<string> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json, text/plain;q=0.9, */*;q=0.8',
    },
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(text || `Request failed with status ${response.status}`);
  }
  return text;
}
