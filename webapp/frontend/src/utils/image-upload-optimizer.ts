import { formatUploadFileSize, getDbBackedUploadSizeWarning } from './upload-size';

const OPTIMIZABLE_IMAGE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp', '.bmp', '.avif'];
const REVIEW_IMAGE_MAX_DIMENSION_PX = 2200;
const REVIEW_IMAGE_SOFT_TARGET_BYTES = 1536 * 1024;
const WEBP_QUALITY_STEPS = [0.9, 0.84, 0.78];
const JPEG_QUALITY_STEPS = [0.88, 0.8, 0.72];

export type PreparedDbBackedUploadFile = {
  file: File;
  displayName: string;
  warning: string | null;
};

const getImageExtension = (fileName: string) => {
  const lower = fileName.toLowerCase();
  const matched = OPTIMIZABLE_IMAGE_EXTENSIONS.find((extension) => lower.endsWith(extension));
  return matched ?? null;
};

const isOptimizableImage = (file: File) => {
  if (file.type.startsWith('image/')) {
    return file.type !== 'image/gif' && file.type !== 'image/svg+xml';
  }
  return getImageExtension(file.name) != null;
};

const replaceExtension = (fileName: string, nextExtension: string) => {
  const currentExtension = getImageExtension(fileName);
  if (!currentExtension) {
    return `${fileName || 'image'}.${nextExtension}`;
  }
  return `${fileName.slice(0, -currentExtension.length)}.${nextExtension}`;
};

const combineWarnings = (...warnings: Array<string | null | undefined>) => {
  const filtered = warnings.filter((warning): warning is string => Boolean(warning?.trim()));
  if (filtered.length === 0) {
    return null;
  }
  return filtered.join(' ');
};

const loadImageBitmap = async (file: File): Promise<ImageBitmap | HTMLImageElement> => {
  if (typeof createImageBitmap === 'function') {
    return createImageBitmap(file);
  }

  const objectUrl = URL.createObjectURL(file);
  try {
    return await new Promise<HTMLImageElement>((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(`Failed to load image ${file.name}`));
      image.src = objectUrl;
    });
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
};

const getRenderedSize = (
  image: ImageBitmap | HTMLImageElement,
): { width: number; height: number } => {
  if ('width' in image && 'height' in image) {
    return { width: image.width, height: image.height };
  }
  return { width: 0, height: 0 };
};

const createCanvas = (width: number, height: number) => {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  return canvas;
};

const encodeCanvasToBlob = (
  canvas: HTMLCanvasElement,
  contentType: string,
  quality: number,
): Promise<Blob> =>
  new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob == null) {
          reject(new Error('Failed to encode image'));
          return;
        }
        resolve(blob);
      },
      contentType,
      quality,
    );
  });

type EncodedImageResult = {
  blob: Blob;
  contentType: string;
  extension: string;
};

const encodeOptimizedImage = async (canvas: HTMLCanvasElement): Promise<EncodedImageResult> => {
  let bestBlob: Blob | null = null;
  for (const quality of WEBP_QUALITY_STEPS) {
    const blob = await encodeCanvasToBlob(canvas, 'image/webp', quality);
    if (blob.type === 'image/webp') {
      if (bestBlob == null || blob.size < bestBlob.size) {
        bestBlob = blob;
      }
      if (blob.size <= REVIEW_IMAGE_SOFT_TARGET_BYTES) {
        break;
      }
    }
  }

  if (bestBlob != null) {
    return {
      blob: bestBlob,
      contentType: 'image/webp',
      extension: 'webp',
    };
  }

  let bestJpegBlob: Blob | null = null;
  for (const quality of JPEG_QUALITY_STEPS) {
    const blob = await encodeCanvasToBlob(canvas, 'image/jpeg', quality);
    if (bestJpegBlob == null || blob.size < bestJpegBlob.size) {
      bestJpegBlob = blob;
    }
    if (blob.size <= REVIEW_IMAGE_SOFT_TARGET_BYTES) {
      break;
    }
  }

  if (bestJpegBlob == null) {
    throw new Error('Failed to encode image');
  }

  return {
    blob: bestJpegBlob,
    contentType: 'image/jpeg',
    extension: 'jpg',
  };
};

const maybeOptimizeImageFile = async (file: File): Promise<File | null> => {
  if (!isOptimizableImage(file)) {
    return null;
  }

  if (file.size <= REVIEW_IMAGE_SOFT_TARGET_BYTES) {
    return null;
  }

  const image = await loadImageBitmap(file);
  try {
    const { width, height } = getRenderedSize(image);
    if (width <= 0 || height <= 0) {
      return null;
    }

    const longestSide = Math.max(width, height);
    const scale =
      longestSide > REVIEW_IMAGE_MAX_DIMENSION_PX ? REVIEW_IMAGE_MAX_DIMENSION_PX / longestSide : 1;
    const targetWidth = Math.max(1, Math.round(width * scale));
    const targetHeight = Math.max(1, Math.round(height * scale));
    const shouldResize = targetWidth !== width || targetHeight !== height;

    const canvas = createCanvas(targetWidth, targetHeight);
    const context = canvas.getContext('2d');
    if (context == null) {
      return null;
    }

    context.drawImage(image, 0, 0, targetWidth, targetHeight);

    const encodedImage = await encodeOptimizedImage(canvas);

    if (!shouldResize && encodedImage.blob.size >= file.size * 0.95) {
      return null;
    }

    return new File([encodedImage.blob], replaceExtension(file.name, encodedImage.extension), {
      type: encodedImage.contentType,
      lastModified: file.lastModified,
    });
  } finally {
    if ('close' in image && typeof image.close === 'function') {
      image.close();
    }
  }
};

export async function prepareDbBackedUploadFile(
  file: File,
  options?: {
    optimizeImages?: boolean;
  },
): Promise<PreparedDbBackedUploadFile> {
  const displayName = file.name;
  const optimizeImages = options?.optimizeImages ?? false;

  if (!optimizeImages) {
    return {
      file,
      displayName,
      warning: getDbBackedUploadSizeWarning(file),
    };
  }

  try {
    const optimizedFile = await maybeOptimizeImageFile(file);
    if (!optimizedFile) {
      return {
        file,
        displayName,
        warning: getDbBackedUploadSizeWarning(file),
      };
    }

    return {
      file: optimizedFile,
      displayName,
      warning: combineWarnings(
        `Optimized before upload (${formatUploadFileSize(file.size)} -> ${formatUploadFileSize(
          optimizedFile.size,
        )}).`,
        getDbBackedUploadSizeWarning(optimizedFile),
      ),
    };
  } catch {
    return {
      file,
      displayName,
      warning: getDbBackedUploadSizeWarning(file),
    };
  }
}
