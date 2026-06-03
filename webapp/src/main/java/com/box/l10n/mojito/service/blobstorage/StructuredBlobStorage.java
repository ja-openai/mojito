package com.box.l10n.mojito.service.blobstorage;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Layer on top of {@link BlobStorage} to avoid naming clash between different Mojito services.
 *
 * <p>Use this class instead of using directly {@link BlobStorage}
 */
@Component
public class StructuredBlobStorage {

  BlobStorageRouter blobStorageRouter;

  public StructuredBlobStorage(BlobStorageRouter blobStorageRouter) {
    this.blobStorageRouter = blobStorageRouter;
  }

  public Optional<String> getString(Prefix prefix, String name) {
    return getBlobStorage(prefix).getString(getFullName(prefix, name));
  }

  public Optional<byte[]> getBytes(Prefix prefix, String name) {
    return getBlobStorage(prefix).getBytes(getFullName(prefix, name));
  }

  public void put(Prefix prefix, String name, String content, Retention retention) {
    getBlobStorage(prefix).put(getFullName(prefix, name), content, retention);
  }

  public void putBytes(Prefix prefix, String name, byte[] content, Retention retention) {
    getBlobStorage(prefix).put(getFullName(prefix, name), content, retention);
  }

  public void delete(Prefix prefix, String name) {
    getBlobStorage(prefix).delete(getFullName(prefix, name));
  }

  public boolean exists(Prefix prefix, String name) {
    return getBlobStorage(prefix).exists(getFullName(prefix, name));
  }

  String getFullName(Prefix prefix, String name) {
    return prefix.toString().toLowerCase() + "/" + name;
  }

  BlobStorage getBlobStorage(Prefix prefix) {
    return blobStorageRouter.getBlobStorage(prefix);
  }

  public enum Prefix {
    POLLABLE_TASK,
    IMAGE,
    MULTI_BRANCH_STATE,
    TEXT_UNIT_DTOS_CACHE,
    TEXT_UNIT_WS_SEARCH_ASYNC,
    REVIEW_PROJECT_REQUEST_SEARCH_ASYNC,
    LINGUIST_TIME_SPENT_REPORT_ASYNC,
    LINGUIST_TIME_SPENT_RECOMPUTE_ASYNC,
    TERM_INDEX_ENTRY_SEARCH_ASYNC,
    GLOSSARY_TERM_INDEX_SUGGESTION_SEARCH_ASYNC,
    CLOB_STORAGE_WS,
    AI_TRANSLATE_WS,
    AI_TRANSLATE_LINEAGE,
    AI_TRANSALATE_NO_BATCH_OUTPUT,
    AI_REVIEW_WS
  }
}
