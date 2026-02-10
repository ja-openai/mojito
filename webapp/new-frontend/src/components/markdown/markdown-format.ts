const SAFE_LINK_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);
const IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg', '.avif'];
const VIDEO_EXTENSIONS = ['.mp4', '.mov', '.webm', '.ogv', '.ogg', '.m4v', '.mkv'];
const PDF_EXTENSIONS = ['.pdf'];

function escapeHtml(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function sanitizeLinkUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) {
    return '#';
  }
  if (trimmed.startsWith('/') || trimmed.startsWith('#')) {
    return trimmed;
  }
  try {
    const parsed = new URL(trimmed);
    if (SAFE_LINK_PROTOCOLS.has(parsed.protocol)) {
      return parsed.toString();
    }
  } catch {
    return '#';
  }
  return '#';
}

function stripQueryAndHash(url: string): string {
  return url.split('#')[0]?.split('?')[0] ?? url;
}

function isImageUrl(url: string): boolean {
  const lower = stripQueryAndHash(url).toLowerCase();
  return lower.startsWith('data:image/') || IMAGE_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

function isVideoUrl(url: string): boolean {
  const lower = stripQueryAndHash(url).toLowerCase();
  return lower.startsWith('data:video/') || VIDEO_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

function isPdfUrl(url: string): boolean {
  const lower = stripQueryAndHash(url).toLowerCase();
  return lower.startsWith('data:application/pdf') || PDF_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

function mediaMarkdownToHtml(label: string, rawUrl: string): string | null {
  const url = sanitizeLinkUrl(rawUrl);
  if (url === '#') {
    return null;
  }
  const safeUrl = escapeHtml(url);
  const labelTrimmed = label.trim();
  const safeLabel = escapeHtml(labelTrimmed || 'Attachment');

  if (isImageUrl(url)) {
    return `<img class="markdown-preview__media markdown-preview__media--image" src="${safeUrl}" alt="${safeLabel}" loading="lazy" />`;
  }
  if (isVideoUrl(url)) {
    return `<video class="markdown-preview__media markdown-preview__media--video" src="${safeUrl}" controls preload="metadata"></video>`;
  }
  if (isPdfUrl(url)) {
    const pdfLabel = labelTrimmed ? `: ${escapeHtml(labelTrimmed)}` : '';
    return `<a class="markdown-preview__media-link" href="${safeUrl}" target="_blank" rel="noreferrer">Open PDF${pdfLabel}</a>`;
  }

  return null;
}

function parseInlineMarkdown(value: string): string {
  const escaped = escapeHtml(value);
  const codeSegments: string[] = [];
  const withCodeTokens = escaped.replace(/`([^`]+)`/g, (_match, codeText: string) => {
    const idx = codeSegments.push(`<code>${codeText}</code>`) - 1;
    return `@@CODE_SEGMENT_${idx}@@`;
  });

  const mediaSegments: string[] = [];
  const withMediaTokens = withCodeTokens.replace(
    /!\[([^\]]*)\]\(([^)\s]+)\)/g,
    (_match, label: string, href: string) => {
      const mediaHtml = mediaMarkdownToHtml(label, href);
      if (!mediaHtml) {
        return `![${label}](${href})`;
      }
      const idx = mediaSegments.push(mediaHtml) - 1;
      return `@@MEDIA_SEGMENT_${idx}@@`;
    },
  );

  const withLinks = withMediaTokens.replace(
    /\[([^\]]+)\]\(([^)\s]+)\)/g,
    (_match, label: string, href: string) => {
      const mediaHtml = mediaMarkdownToHtml(label, href);
      if (mediaHtml) {
        return mediaHtml;
      }
      return `<a href="${escapeHtml(sanitizeLinkUrl(href))}" target="_blank" rel="noreferrer">${label}</a>`;
    },
  );

  const withBold = withLinks.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  const withItalic = withBold.replace(
    /(^|[^\w*])\*([^*\n]+)\*(?=([^\w*]|$))/g,
    '$1<em>$2</em>',
  );

  const withCodeRestored = withItalic.replace(/@@CODE_SEGMENT_(\d+)@@/g, (_match, idxString: string) => {
    const idx = Number(idxString);
    return Number.isFinite(idx) ? (codeSegments[idx] ?? '') : '';
  });

  return withCodeRestored.replace(/@@MEDIA_SEGMENT_(\d+)@@/g, (_match, idxString: string) => {
    const idx = Number(idxString);
    return Number.isFinite(idx) ? (mediaSegments[idx] ?? '') : '';
  });
}

function normalizeMarkdownOutput(markdown: string): string {
  return markdown
    .replace(/\r\n/g, '\n')
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

export function markdownToHtml(markdown: string): string {
  const normalized = markdown.replace(/\r\n/g, '\n').trim();
  if (!normalized) {
    return '';
  }

  const lines = normalized.split('\n');
  const html: string[] = [];
  let paragraphLines: string[] = [];
  let listType: 'ul' | 'ol' | null = null;
  let listItems: string[] = [];
  let quoteLines: string[] = [];
  let inCodeBlock = false;
  let codeLines: string[] = [];

  const flushParagraph = () => {
    if (paragraphLines.length === 0) {
      return;
    }
    const content = parseInlineMarkdown(paragraphLines.join('\n')).replace(/\n/g, '<br/>');
    html.push(`<p>${content}</p>`);
    paragraphLines = [];
  };

  const flushList = () => {
    if (!listType || listItems.length === 0) {
      listType = null;
      listItems = [];
      return;
    }
    const items = listItems.map((item) => `<li>${parseInlineMarkdown(item)}</li>`).join('');
    html.push(`<${listType}>${items}</${listType}>`);
    listType = null;
    listItems = [];
  };

  const flushQuote = () => {
    if (quoteLines.length === 0) {
      return;
    }
    const rendered = quoteLines.map((line) => parseInlineMarkdown(line)).join('<br/>');
    html.push(`<blockquote><p>${rendered}</p></blockquote>`);
    quoteLines = [];
  };

  const flushCode = () => {
    if (codeLines.length === 0) {
      return;
    }
    html.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
    codeLines = [];
  };

  for (const line of lines) {
    const trimmed = line.trim();

    if (inCodeBlock) {
      if (trimmed.startsWith('```')) {
        inCodeBlock = false;
        flushCode();
      } else {
        codeLines.push(line);
      }
      continue;
    }

    if (trimmed.startsWith('```')) {
      flushParagraph();
      flushList();
      flushQuote();
      inCodeBlock = true;
      continue;
    }

    if (!trimmed) {
      flushParagraph();
      flushList();
      flushQuote();
      continue;
    }

    const headingMatch = line.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch) {
      flushParagraph();
      flushList();
      flushQuote();
      const level = headingMatch[1].length;
      html.push(`<h${level}>${parseInlineMarkdown(headingMatch[2])}</h${level}>`);
      continue;
    }

    const quoteMatch = line.match(/^>\s?(.*)$/);
    if (quoteMatch) {
      flushParagraph();
      flushList();
      quoteLines.push(quoteMatch[1]);
      continue;
    }
    flushQuote();

    const unorderedMatch = line.match(/^[-*]\s+(.+)$/);
    if (unorderedMatch) {
      flushParagraph();
      if (listType !== 'ul') {
        flushList();
        listType = 'ul';
      }
      listItems.push(unorderedMatch[1]);
      continue;
    }

    const orderedMatch = line.match(/^\d+\.\s+(.+)$/);
    if (orderedMatch) {
      flushParagraph();
      if (listType !== 'ol') {
        flushList();
        listType = 'ol';
      }
      listItems.push(orderedMatch[1]);
      continue;
    }

    flushList();
    paragraphLines.push(line);
  }

  flushParagraph();
  flushList();
  flushQuote();
  if (inCodeBlock) {
    flushCode();
  }

  return html.join('\n');
}

function nodeToMarkdown(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.textContent ?? '';
  }

  if (node.nodeType !== Node.ELEMENT_NODE) {
    return '';
  }

  const element = node as HTMLElement;
  const tag = element.tagName.toLowerCase();
  const childText = Array.from(element.childNodes)
    .map((child) => nodeToMarkdown(child))
    .join('');

  switch (tag) {
    case 'br':
      return '\n';
    case 'strong':
    case 'b':
      return `**${childText}**`;
    case 'em':
    case 'i':
      return `*${childText}*`;
    case 'code': {
      if (element.parentElement?.tagName.toLowerCase() === 'pre') {
        return element.textContent ?? '';
      }
      return `\`${childText}\``;
    }
    case 'a': {
      const href = element.getAttribute('href') ?? '#';
      const label = childText.trim() || href;
      return `[${label}](${href})`;
    }
    case 'img': {
      const src = element.getAttribute('src') ?? '';
      if (!src) {
        return '';
      }
      const alt = element.getAttribute('alt') ?? 'image';
      return `![${alt}](${src})`;
    }
    case 'video': {
      const src =
        element.getAttribute('src') ??
        element.querySelector('source')?.getAttribute('src') ??
        '';
      if (!src) {
        return '';
      }
      return `![video](${src})`;
    }
    case 'object':
    case 'embed':
    case 'iframe': {
      const src = element.getAttribute('data') ?? element.getAttribute('src') ?? '';
      if (!src) {
        return '';
      }
      return `![attachment](${src})`;
    }
    case 'h1':
    case 'h2':
    case 'h3':
    case 'h4':
    case 'h5':
    case 'h6': {
      const level = Number(tag.slice(1));
      return `${'#'.repeat(level)} ${childText.trim()}\n\n`;
    }
    case 'blockquote': {
      const lines = childText
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => `> ${line}`);
      return lines.join('\n') + '\n\n';
    }
    case 'ul': {
      const items = Array.from(element.children)
        .filter((child) => child.tagName.toLowerCase() === 'li')
        .map((child) => `- ${nodeToMarkdown(child).trim()}`);
      return items.join('\n') + '\n\n';
    }
    case 'ol': {
      const items = Array.from(element.children)
        .filter((child) => child.tagName.toLowerCase() === 'li')
        .map((child, idx) => `${idx + 1}. ${nodeToMarkdown(child).trim()}`);
      return items.join('\n') + '\n\n';
    }
    case 'li':
      return childText;
    case 'pre': {
      const text = element.textContent ?? '';
      return `\`\`\`\n${text.replace(/\n+$/g, '')}\n\`\`\`\n\n`;
    }
    case 'p':
    case 'div':
    case 'section':
    case 'article': {
      const block = childText.trim();
      return block ? `${block}\n\n` : '';
    }
    case 'span': {
      const style = (element.getAttribute('style') ?? '').toLowerCase();
      const isBold = style.includes('font-weight: bold') || style.includes('font-weight:bold');
      const isItalic = style.includes('font-style: italic') || style.includes('font-style:italic');
      if (isBold && isItalic) {
        return `***${childText}***`;
      }
      if (isBold) {
        return `**${childText}**`;
      }
      if (isItalic) {
        return `*${childText}*`;
      }
      return childText;
    }
    default:
      return childText;
  }
}

export function htmlToMarkdown(html: string): string {
  if (!html.trim()) {
    return '';
  }

  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');

  const markdown = Array.from(doc.body.childNodes)
    .map((node) => nodeToMarkdown(node))
    .join('');

  return normalizeMarkdownOutput(markdown);
}
