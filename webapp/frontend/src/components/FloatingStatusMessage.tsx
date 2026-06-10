import './floating-status-message.css';

import type { ReactNode } from 'react';

type FloatingStatusMessageKind = 'success' | 'error';

type FloatingStatusMessageProps = {
  message?: ReactNode;
  kind?: FloatingStatusMessageKind;
  className?: string;
};

export function FloatingStatusMessage({
  message,
  kind = 'success',
  className,
}: FloatingStatusMessageProps) {
  if (!message) {
    return null;
  }

  const isError = kind === 'error';

  return (
    <div
      className={[
        'floating-status-message',
        isError ? 'floating-status-message--error' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      role={isError ? 'alert' : 'status'}
      aria-live={isError ? 'assertive' : 'polite'}
    >
      {message}
    </div>
  );
}
