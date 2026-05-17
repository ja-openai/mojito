export const DB_BACKED_UPLOAD_WARNING_SIZE_BYTES = 8 * 1024 * 1024;
export const DB_BACKED_UPLOAD_MAX_SIZE_BYTES = 20 * 1024 * 1024;

export const formatUploadFileSize = (sizeBytes: number) => {
  const sizeMb = sizeBytes / (1024 * 1024);
  return `${sizeMb.toFixed(sizeMb >= 10 ? 0 : 1)} MB`;
};

export const getDbBackedUploadSizeError = (file: Pick<File, 'size'>) => {
  if (file.size > DB_BACKED_UPLOAD_MAX_SIZE_BYTES) {
    return `File is too large (${formatUploadFileSize(file.size)}). Maximum size is ${formatUploadFileSize(
      DB_BACKED_UPLOAD_MAX_SIZE_BYTES,
    )}.`;
  }
  return null;
};

export const getDbBackedUploadSizeWarning = (file: Pick<File, 'size'>) => {
  if (
    file.size > DB_BACKED_UPLOAD_WARNING_SIZE_BYTES &&
    file.size <= DB_BACKED_UPLOAD_MAX_SIZE_BYTES
  ) {
    return `Large file (${formatUploadFileSize(file.size)}). Upload may be slow because attachments are stored in the database.`;
  }
  return null;
};

export const canUploadDbBackedFile = (file: Pick<File, 'size'>) =>
  getDbBackedUploadSizeError(file) == null;
