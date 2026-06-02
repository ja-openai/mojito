# Azure Blob Storage

## Summary

Mojito now has three `BlobStorage` implementations:

- `DatabaseBlobStorage`: default implementation that stores blob bytes in the `mblob` table.
- `S3BlobStorage`: stores blobs in S3 under `l10n.blob-storage.s3.prefix`.
- `AzureBlobStorage`: stores blobs in Azure Blob Storage under `l10n.blob-storage.azure.prefix`.

## Behavior comparison

| Behavior | Database/JDBC | AWS S3 | Azure Blob Storage |
| --- | --- | --- | --- |
| Write | Upserts one `mblob` row by name. | Uploads object to bucket + prefix. | Uploads blob to container + prefix. |
| Read missing | Empty `Optional`. | Empty `Optional` for `NoSuchKey`. | Empty `Optional` for `BlobNotFound`. |
| Delete | Deletes matching `mblob` row. | Deletes object. | Deletes blob if present. |
| Exists | Looks up row by name. | Uses `doesObjectExist`. | Uses `BlobClient.exists`. |
| Text encoding | Shared interface uses UTF-8. | Sets `text/plain` and UTF-8 encoding. | Sets `text/plain` and UTF-8 encoding. |
| Retention marker | Stores `expire_after_seconds` for `MIN_1_DAY`. | Adds `retention=<Retention>` object tag. | Adds `retention=<Retention>` blob index tag. |
| Cleanup | Internal Quartz cleanup job runs every 5 minutes. | External S3 lifecycle rule required. | External Azure lifecycle management rule required. |

## Configuration

Connection string:

```properties
l10n.azure.blob-storage.enabled=true
l10n.azure.blob-storage.connection-string=...
l10n.azure.blob-storage.container=mojito
l10n.blob-storage.type=azure
l10n.blob-storage.azure.prefix=mojito
```

Managed identity or other default Azure credentials:

```properties
l10n.azure.blob-storage.enabled=true
l10n.azure.blob-storage.endpoint=https://<account>.blob.core.windows.net
l10n.azure.blob-storage.container=mojito
l10n.blob-storage.type=azure
l10n.blob-storage.azure.prefix=mojito
```

## Remaining gaps

- Azure and S3 retention cleanup is not owned by Mojito. Operators must configure provider lifecycle rules that match `retention=MIN_1_DAY`; otherwise temporary blobs are retained indefinitely.
- Image storage can use Azure through `l10n.image-service.storage.type=blobStorage` or `blobStorageFallback` with `l10n.blob-storage.type=azure`.
- There is no live Azure integration test in the default suite. The unit tests cover request shape, not an actual Azure account/container.
