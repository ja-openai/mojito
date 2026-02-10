import { useMemo } from 'react';

import { markdownToHtml } from './markdown-format';
import './markdown.css';

type Props = {
  markdown: string;
  className?: string;
  emptyLabel?: string;
};

export function MarkdownPreview({
  markdown,
  className,
  emptyLabel = 'No description provided.',
}: Props) {
  const html = useMemo(() => markdownToHtml(markdown), [markdown]);
  const normalized = markdown.trim();
  const composedClassName = `markdown-preview${className ? ` ${className}` : ''}`;

  if (!normalized) {
    return <div className={`${composedClassName} markdown-preview--empty`}>{emptyLabel}</div>;
  }

  return <div className={composedClassName} dangerouslySetInnerHTML={{ __html: html }} />;
}
