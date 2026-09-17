import { isMacPlatform } from '../../utils/keyboardShortcuts';
import {
  type DocumentPreviewLink as PreviewLink,
  resolveDocumentPreviewLink,
} from './document-preview-link';

export function DocumentPreviewLink({
  label,
  href,
  assetPath,
  editing,
  disabled,
  onFollow,
}: {
  label: string;
  href: string;
  assetPath: string;
  editing: boolean;
  disabled: boolean;
  onFollow: (link: PreviewLink) => Promise<void>;
}) {
  const link = resolveDocumentPreviewLink(href, assetPath);
  if (!link)
    return (
      <span className="review-project-document__link" title={href}>
        {label}
      </span>
    );
  return (
    <a
      className="review-project-document__link"
      href={link.type === 'external' ? link.href : undefined}
      role="link"
      tabIndex={0}
      target={link.type === 'external' ? '_blank' : undefined}
      rel={link.type === 'external' ? 'noopener noreferrer' : undefined}
      title={editing ? `${href} · ${isMacPlatform() ? '⌘' : 'Ctrl'}-click to follow` : href}
      onClick={(event) => {
        if (editing && event.detail !== 0 && !event.metaKey && !event.ctrlKey && !event.altKey) {
          event.preventDefault();
          // Let the passage handle an ordinary click as an edit selection.
          return;
        }
        event.stopPropagation();
        if (disabled || link.type !== 'external') {
          event.preventDefault();
          if (!disabled) void onFollow(link);
        } else if (event.altKey) {
          // Alt-click normally downloads a link in some browsers.
          event.preventDefault();
          window.open(link.href, '_blank', 'noopener,noreferrer');
        }
      }}
      onDoubleClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === 'Enter' && (disabled || link.type !== 'external')) {
          event.preventDefault();
          if (!disabled) void onFollow(link);
        }
      }}
    >
      {label}
    </a>
  );
}
