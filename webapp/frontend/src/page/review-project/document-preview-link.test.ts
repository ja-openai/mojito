import { describe, expect, it } from 'vitest';

import { resolveDocumentPreviewLink } from './document-preview-link';

describe('resolveDocumentPreviewLink', () => {
  it.each([
    ['guide.html', 'index.mdx', 'guide.mdx'],
    ['index.html', 'guide.mdx', 'index.mdx'],
    ['./guide.htm', 'content/index.mdx', 'content/guide.mdx'],
    ['../guide.html', 'pages/start/index.mdx', 'pages/guide.mdx'],
    ['/pages/guide.mdx', 'pages/start/index.mdx', 'pages/guide.mdx'],
    ['guide', 'content/index.mdx', 'content/guide.mdx'],
    ['guides/', 'content/index.mdx', 'content/guides/index.mdx'],
    ['/', 'content/index.mdx', 'index.mdx'],
    ['../', 'content/index.mdx', 'index.mdx'],
    ['../guide', 'content/index.mdx', 'guide.mdx'],
    ['caf%C3%A9.html', 'content/index.mdx', 'content/café.mdx'],
    ['guide.html', 'content/100%/index.mdx', 'content/100%/guide.mdx'],
    ['%2e%2e/guide.mdx', 'content/index.mdx', 'guide.mdx'],
  ])('resolves %s from %s to %s', (href, base, assetPath) => {
    expect(resolveDocumentPreviewLink(href, base)).toEqual({
      type: 'asset',
      assetPath,
      fragment: '',
    });
  });

  it('ignores a page query and decodes the fragment without including either in the asset path', () => {
    expect(
      resolveDocumentPreviewLink('guide.html?mode=team#premi%C3%A8re%20partie', 'index.mdx'),
    ).toEqual({ type: 'asset', assetPath: 'guide.mdx', fragment: 'première partie' });
  });

  it('keeps a fragment-only link within the current page', () => {
    expect(resolveDocumentPreviewLink('#premi%C3%A8re-partie', 'index.mdx')).toEqual({
      type: 'fragment',
      fragment: 'première-partie',
    });
    expect(resolveDocumentPreviewLink('#', 'index.mdx')).toEqual({
      type: 'fragment',
      fragment: '',
    });
  });

  it.each([
    ['https://example.com/guide?locale=fr#start', 'https://example.com/guide?locale=fr#start'],
    ['HTTP://example.com', 'http://example.com/'],
    ['mailto:translator@example.com?subject=Hello', 'mailto:translator@example.com?subject=Hello'],
  ])('allows an explicitly safe external URL %s', (href, result) => {
    expect(resolveDocumentPreviewLink(href, 'index.mdx')).toEqual({
      type: 'external',
      href: result,
    });
  });

  it.each([
    '',
    ' ',
    'javascript:alert(1)',
    'JAVASCRIPT:alert(1)',
    'javascript%3Aalert(1)',
    'data:text/html,hello',
    'file:///etc/passwd',
    'ftp://example.com/guide',
    '//example.com/guide',
    'https:example.com',
    'https:///example.com',
    'https://',
    'mailto:',
    'guide\\other.html',
    'guide%5cother.html',
    'guide%2fother.html',
    'guide.html\n',
    'java\tscript:alert(1)',
    'java%09script:alert(1)',
    'guide.html#%0a',
    'guide.html#bad%',
    'bad%path.html',
    'guide.html?bad=%',
    '../guide.html',
    '/../../guide.html',
    '%2e%2e/guide.html',
    '%2e%2e%2fguide.html',
    'image.png',
    '?tab=guide',
    '{getUrl()}',
    '<script>',
    'guide.html "title"',
  ])('leaves unsafe or unsupported links inert: %s', (href) => {
    expect(resolveDocumentPreviewLink(href, 'index.mdx')).toBeNull();
  });

  it('rejects traversal beyond the repository root even from a nested page', () => {
    expect(resolveDocumentPreviewLink('../../guide.html', 'content/index.mdx')).toBeNull();
  });
});
