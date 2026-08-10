# Azure Blob Storage

## Summary

Mojito now has four `BlobStorage` implementations:

- `DatabaseBlobStorage`: default implementation that stores blob bytes in the `mblob` table.
- `S3BlobStorage`: stores blobs in S3 under `l10n.blob-storage.s3.prefix`.
- `AzureBlobStorage`: stores blobs in Azure Blob Storage under `l10n.blob-storage.azure.prefix`.
- `AzureDatabaseFallbackBlobStorage`: writes to Azure and backfills missing Azure objects from
  existing `mblob` rows on read.

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
l10n.blob-storage.default-type=azure
l10n.blob-storage.azure.prefix=mojito
```

Managed identity or other default Azure credentials:

```properties
l10n.azure.blob-storage.enabled=true
l10n.azure.blob-storage.endpoint=https://<account>.blob.core.windows.net
l10n.azure.blob-storage.container=mojito
l10n.blob-storage.default-type=azure
l10n.blob-storage.azure.prefix=mojito
```

Per-prefix routing:

```properties
l10n.blob-storage.default-type=azure
l10n.blob-storage.routing.prefixes.pollable-task=database
l10n.blob-storage.routing.prefixes.image=azure
```

`StructuredBlobStorage` uses semantic prefixes, not repository shape, to choose a backend. This lets control-plane data such as `pollable-task` remain DB-backed while large artifact-like prefixes use Azure or S3.

Migration fallback is opt-in through a composite backend. Route an individual prefix to it while
moving existing objects away from MySQL:

```properties
l10n.blob-storage.routing.prefixes.pollable-task=azure-with-database-fallback
```

Alternatively, set `l10n.blob-storage.default-type=azure-with-database-fallback` to use it for every
prefix without an explicit route. The composite initializes both Azure and database storage, reads
Azure first, and checks the same full object name in the legacy `mblob` table only when Azure
reports the object missing. When a legacy object exists, its string or binary contents are written
to Azure with the original temporary or permanent retention policy before being returned. Future
reads then hit Azure directly. Provider read or backfill errors propagate; normal writes, deletes,
and existence checks remain Azure-only. Switch migrated routes to plain `azure` after the legacy
data is no longer needed to avoid a MySQL lookup for every missing remote object.

`AzureDatabaseFallbackBlobStorage.read` counts migration reads by bounded semantic `prefix`,
`format`, and `result` tags. Results distinguish `azure_hit`, `database_hit`, `miss`,
`azure_error`, `database_error`, and `backfill_error`. Text-unit DTO caches also expose
`TextUnitDTOsCacheBlobStorage.lookup` with `format={json|smile}` and `result={hit|miss}` to measure
logical cache reuse independently from migration progress.

`l10n.blob-storage.default-type` selects the backend for prefixes without an explicit route.
The old `l10n.blob-storage.type` setting remains supported temporarily for existing deployments,
but logs a deprecation warning. When both settings are present, `default-type` takes precedence.

## Image migration

Image services honor the `image` prefix route, so existing database images can be migrated to Azure
without changing the default database-backed blob-storage backend. A remote image route
automatically enables database fallback for existing images; no separate image-storage backend
setting is required:

```properties
l10n.blob-storage.routing.prefixes.image=azure
l10n.image-service.migration.enabled=true
l10n.image-service.migration.cron=0 0 * * * ?
l10n.image-service.migration.batch-size=25
l10n.image-service.migration.delete-source=false
```

The Quartz job is disabled unless explicitly enabled and requires a remote image backend. It scans
database images in primary-key order, skips images already present in blob storage, and limits each
run to the configured number of uploads or source deletions. Source rows are removed only when
`delete-source=true` and the remote bytes have been verified against the database image. Explicit
legacy `l10n.image-service.storage.type` settings still override automatic image routing.

## Staged rollout and monitoring

Enable `l10n.azure.blob-storage.enabled` and configure the endpoint/container while keeping
`l10n.blob-storage.default-type=database` to initialize the Azure client without changing any active blob
routes. Admins can open **Azure Storage** next to **Database monitoring** in the account menu to
inspect container access, current per-prefix routing, and run an explicit write/read/delete probe.
The probe uses the same tagged upload as production writes, including the `retention=MIN_1_DAY`
blob index tag, so missing blob-tag permissions fail the write check instead of reporting false
readiness.
The monitoring API is protected by the existing admin-only `/api/monitoring/**` security rule.

Only route selected prefixes to Azure after the status page and active probe are healthy. Existing
database-backed prefixes remain unchanged until explicitly reconfigured. Azure being disabled or
unavailable is reported on the monitoring page without changing application readiness or silently
falling back to MySQL.

## Remaining gaps

- Database-backed blobs can now be drained by admin-managed prefix cleanup policies under
  `/settings/system/blob-cleanup`. Scheduling is opt-in, policies are disabled by default,
  and policies delete only rows with a TTL,
  use the existing `name` index in independently committed batches, and continue until the prefix
  is drained unless an explicit batch cap or stop request is configured. MySQL skips locked rows
  and retries transient lock conflicts. The policy worker is independent of the legacy generic
  expired-blob cleanup job; keep the generic job disabled on large `mblob` tables.
- Azure and S3 retention cleanup is not owned by Mojito. Operators must configure provider lifecycle rules that match `retention=MIN_1_DAY`; otherwise temporary blobs are retained indefinitely.
- There is no live Azure integration test in the default suite. The unit tests cover request shape, not an actual Azure account/container.
- Production deployments still need an explicit prefix policy. Recommended initial policy is to keep `pollable-task` in the database and route only large artifact-like prefixes to remote object storage.
