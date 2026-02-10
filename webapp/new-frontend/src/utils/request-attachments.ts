export type RequestAttachmentKind = 'image' | 'video' | 'pdf' | 'file';

const IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg', '.avif'];
const VIDEO_EXTENSIONS = ['.mp4', '.mov', '.webm', '.ogv', '.ogg', '.m4v', '.mkv'];
const PDF_EXTENSIONS = ['.pdf'];

const MIME_EXTENSION_MAP: Record<string, string> = {
  'application/pdf': 'pdf',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/gif': 'gif',
  'image/webp': 'webp',
  'image/bmp': 'bmp',
  'image/svg+xml': 'svg',
  'image/avif': 'avif',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
  'video/ogg': 'ogv',
  'video/x-matroska': 'mkv',
};

const getUploadFileExtension = (file: File) => {
  const trimmedName = file.name.trim();
  const lastDot = trimmedName.lastIndexOf('.');
  let extension =
    lastDot > 0 && lastDot < trimmedName.length - 1 ? trimmedName.slice(lastDot + 1) : '';
  if (!extension && file.type) {
    extension = MIME_EXTENSION_MAP[file.type.toLowerCase()] ?? '';
  }
  return extension.toLowerCase().replace(/[^a-z0-9]/g, '');
};

const getUploadFileBaseName = (file: File) => {
  const trimmedName = file.name.trim();
  if (!trimmedName) {
    return 'attachment';
  }
  const lastDot = trimmedName.lastIndexOf('.');
  const rawBase = lastDot > 0 ? trimmedName.slice(0, lastDot) : trimmedName;
  const sanitized = rawBase
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '');
  return (sanitized || 'attachment').slice(0, 80);
};

const getRandomKeySuffix = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto && crypto.randomUUID
    ? crypto.randomUUID().slice(0, 8)
    : Math.random().toString(36).slice(2, 10);

const stripQueryAndHash = (value: string) => value.split('?')[0]?.split('#')[0] ?? value;

export const buildUploadFileKey = (file: File) => {
  const baseName = getUploadFileBaseName(file);
  const ext = getUploadFileExtension(file);
  const suffix = getRandomKeySuffix();
  return ext ? `${baseName}-${suffix}.${ext}` : `${baseName}-${suffix}`;
};

export const resolveAttachmentUrl = (key: string) => {
  const isExternal =
    /^https?:\/\//i.test(key) ||
    key.startsWith('//') ||
    key.startsWith('data:') ||
    key.startsWith('blob:');
  return isExternal ? key : `/api/images/${encodeURIComponent(key)}`;
};

export const isImageAttachmentKey = (key: string) => {
  const lower = stripQueryAndHash(key).toLowerCase();
  return (
    key.startsWith('data:image') ||
    key.startsWith('blob:') ||
    IMAGE_EXTENSIONS.some((ext) => lower.endsWith(ext))
  );
};

export const isVideoAttachmentKey = (key: string) => {
  const lower = stripQueryAndHash(key).toLowerCase();
  return (
    key.startsWith('data:video') ||
    key.startsWith('blob:') ||
    VIDEO_EXTENSIONS.some((ext) => lower.endsWith(ext))
  );
};

export const isPdfAttachmentKey = (key: string) => {
  const lower = stripQueryAndHash(key).toLowerCase();
  return key.startsWith('data:application/pdf') || PDF_EXTENSIONS.some((ext) => lower.endsWith(ext));
};

export const toDescriptionAttachmentMarkdown = (key: string) => {
  const url = resolveAttachmentUrl(key);
  if (isImageAttachmentKey(key)) {
    return `![${key}](${url})`;
  }
  if (isVideoAttachmentKey(key)) {
    return `[video: ${key}](${url})`;
  }
  if (isPdfAttachmentKey(key)) {
    return `[pdf: ${key}](${url})`;
  }
  return `[${key}](${url})`;
};

export const getAttachmentKindFromFile = (file: File): RequestAttachmentKind => {
  if (file.type.startsWith('image/')) {
    return 'image';
  }
  if (file.type.startsWith('video/')) {
    return 'video';
  }
  if (file.type === 'application/pdf') {
    return 'pdf';
  }

  const lower = file.name.toLowerCase();
  if (VIDEO_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
    return 'video';
  }
  if (IMAGE_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
    return 'image';
  }
  if (PDF_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
    return 'pdf';
  }
  return 'file';
};

export const isSupportedRequestAttachmentFile = (file: File) =>
  getAttachmentKindFromFile(file) !== 'file';
