export type DocumentPreviewLink =
  | { type: 'external'; href: string }
  | { type: 'asset'; assetPath: string; fragment: string }
  | { type: 'fragment'; fragment: string };

const PREVIEW_ORIGIN = 'https://document-preview.invalid';

function hasUnsafeCharacters(value: string): boolean {
  return Array.from(value).some(
    (character) =>
      character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127 || character === '\\',
  );
}

/**
 * Resolve site links without sending relative URLs to the Mojito host. The preview
 * convention maps HTML pages to MDX, bare routes to .mdx, and directories to index.mdx.
 * Relative links use the containing page's directory, including links in reused modules.
 */
export function resolveDocumentPreviewLink(
  rawHref: string,
  documentAssetPath: string,
): DocumentPreviewLink | null {
  if (hasUnsafeCharacters(rawHref)) return null;
  const href = rawHref.trim();
  if (!href || /[<>"'{}]/.test(href) || href.startsWith('//')) return null;

  try {
    const decoded = decodeURIComponent(href);
    if (hasUnsafeCharacters(decoded)) return null;
    const scheme = href.match(/^([a-z][a-z\d+.-]*):/i)?.[1]?.toLowerCase();
    if (scheme != null) {
      if (scheme !== 'http' && scheme !== 'https' && scheme !== 'mailto') return null;
      if (scheme !== 'mailto' && !/^https?:\/\/[^/]/i.test(href)) return null;
      const url = new URL(href);
      if (scheme === 'mailto' ? !url.pathname : !url.hostname) return null;
      return { type: 'external', href: url.href };
    }

    const hashIndex = href.indexOf('#');
    const fragment = hashIndex < 0 ? '' : decodeURIComponent(href.slice(hashIndex + 1));
    if (href.startsWith('#')) return { type: 'fragment', fragment };
    const rawPath = href.split(/[?#]/, 1)[0];
    if (!rawPath || /%2f|%5c/i.test(rawPath)) return null;
    const path = decodeURIComponent(rawPath);
    if (path.includes(':')) return null;
    if (hasUnsafeCharacters(documentAssetPath)) return null;

    // URL normalization clamps traversal at the root; reject it instead of matching
    // a different asset after an accidental or malicious excess ".." segment.
    let depth = path.startsWith('/') ? 0 : documentAssetPath.split('/').filter(Boolean).length - 1;
    for (const segment of path.split('/')) {
      if (segment === '..') {
        if (depth === 0) return null;
        depth--;
      } else if (segment && segment !== '.') {
        depth++;
      }
    }

    const basePath = documentAssetPath.split('/').map(encodeURIComponent).join('/');
    const url = new URL(href, `${PREVIEW_ORIGIN}/${basePath.replace(/^\/+/, '')}`);
    if (url.origin !== PREVIEW_ORIGIN) return null;
    let assetPath = decodeURIComponent(url.pathname).replace(/^\//, '');
    if (assetPath.endsWith('/') || !assetPath) assetPath += 'index.mdx';
    else if (/\.html?$/i.test(assetPath)) assetPath = assetPath.replace(/\.html?$/i, '.mdx');
    else if (!assetPath.split('/').pop()!.includes('.')) assetPath += '.mdx';
    else if (!/\.mdx$/i.test(assetPath)) return null;
    return { type: 'asset', assetPath, fragment };
  } catch {
    return null;
  }
}
