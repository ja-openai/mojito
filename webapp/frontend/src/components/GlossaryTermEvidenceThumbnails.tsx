import './glossary-term-evidence-thumbnails.css';

import { useState } from 'react';

import type { ApiGlossaryTermEvidence } from '../api/glossaries';
import { getGlossaryTermScreenshotEvidence } from '../utils/glossaryTermEvidence';
import { resolveAttachmentUrl } from '../utils/request-attachments';
import { Modal } from './Modal';

type Props = {
  evidence: ApiGlossaryTermEvidence[] | null | undefined;
};

export function GlossaryTermEvidenceThumbnails({ evidence }: Props) {
  const screenshots = getGlossaryTermScreenshotEvidence(evidence);
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const selectedScreenshot = selectedIndex == null ? null : screenshots[selectedIndex];

  if (screenshots.length === 0) {
    return null;
  }

  return (
    <div className="glossary-term-evidence-thumbnails">
      {screenshots.map((item, index) => (
        <button
          key={`${item.id ?? index}:${item.imageKey}`}
          type="button"
          className="glossary-term-evidence-thumbnails__item"
          onClick={() => setSelectedIndex(index)}
        >
          <img src={resolveAttachmentUrl(item.imageKey)} alt={item.caption || 'Term screenshot'} />
        </button>
      ))}
      <Modal
        open={selectedScreenshot != null}
        size="xl"
        ariaLabel="Term screenshot"
        onClose={() => setSelectedIndex(null)}
        closeOnBackdrop
        className="glossary-term-evidence-thumbnails__modal"
      >
        {selectedScreenshot ? (
          <>
            <div className="modal__header">
              <div>
                <div className="modal__title">Term screenshot</div>
                <div className="glossary-term-evidence-thumbnails__modal-count">
                  {(selectedIndex ?? 0) + 1} / {screenshots.length}
                </div>
              </div>
              <button
                type="button"
                className="modal__button"
                onClick={() => setSelectedIndex(null)}
              >
                Close
              </button>
            </div>
            <div className="glossary-term-evidence-thumbnails__modal-body">
              <button
                type="button"
                className="glossary-term-evidence-thumbnails__modal-nav"
                onClick={() =>
                  setSelectedIndex(
                    ((selectedIndex ?? 0) - 1 + screenshots.length) % screenshots.length,
                  )
                }
                aria-label="Previous screenshot"
                disabled={screenshots.length < 2}
              >
                ‹
              </button>
              <img
                className="glossary-term-evidence-thumbnails__modal-image"
                src={resolveAttachmentUrl(selectedScreenshot.imageKey)}
                alt={selectedScreenshot.caption || 'Term screenshot'}
              />
              <button
                type="button"
                className="glossary-term-evidence-thumbnails__modal-nav"
                onClick={() => setSelectedIndex(((selectedIndex ?? 0) + 1) % screenshots.length)}
                aria-label="Next screenshot"
                disabled={screenshots.length < 2}
              >
                ›
              </button>
            </div>
            {selectedScreenshot.caption ? (
              <div className="glossary-term-evidence-thumbnails__modal-caption">
                {selectedScreenshot.caption}
              </div>
            ) : null}
          </>
        ) : null}
      </Modal>
    </div>
  );
}
