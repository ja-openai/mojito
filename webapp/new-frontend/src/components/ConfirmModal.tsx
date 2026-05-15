import { useEffect, useId, useState } from 'react';

import { Modal } from './Modal';

type Props = {
  open: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  confirmVariant?: 'danger' | 'primary';
  requireText?: string;
  requireTextLabel?: string;
};

export function ConfirmModal({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
  confirmVariant = 'danger',
  requireText,
  requireTextLabel,
}: Props) {
  const [confirmationValue, setConfirmationValue] = useState('');
  const confirmationInputId = useId();

  useEffect(() => {
    if (!open) {
      setConfirmationValue('');
    }
  }, [open]);

  const isConfirmDisabled =
    typeof requireText === 'string' && confirmationValue.trim() !== requireText;

  return (
    <Modal
      open={open}
      size={requireText ? 'md' : 'sm'}
      role="alertdialog"
      ariaLabel={title}
      className="confirm-modal"
    >
      <div className="modal__title">{title}</div>
      <div className="modal__body">{body}</div>
      {requireText ? (
        <div className="confirm-modal__confirmation">
          <label className="confirm-modal__label" htmlFor={confirmationInputId}>
            {requireTextLabel ?? `Type "${requireText}" to confirm.`}
          </label>
          <input
            id={confirmationInputId}
            type="text"
            className="confirm-modal__input"
            value={confirmationValue}
            onChange={(event) => {
              setConfirmationValue(event.target.value);
            }}
            autoComplete="off"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
          />
        </div>
      ) : null}
      <div className="modal__actions">
        <button type="button" className="modal__button" onClick={onCancel}>
          {cancelLabel}
        </button>
        <button
          type="button"
          className={`modal__button modal__button--${confirmVariant}`}
          onClick={onConfirm}
          disabled={isConfirmDisabled}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
