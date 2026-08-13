# Elasticsearch-compatible text-unit search index

## Scope

OpenSearch provides an optional Elasticsearch-compatible search index for translation-memory text
unit variants. Enabling it does not replace the existing database-backed Workbench search, change
translation writes, or automatically generate embeddings.

The locale-aware index is `tm-text-unit-variants-v1`. Documents include source and target text,
their distinct repository-source and target locales, repository, asset, translation status,
current-variant state, and leveraging metadata. Its mapping also reserves a configurable
1,536-dimensional `knn_vector` field for future semantic retrieval; the current implementation
performs lexical and fuzzy search only.

Source and target strings are routed into separate per-language fields. OpenSearch's installed
language analyzers provide language-appropriate stemming and normalization for supported languages.
Brazilian Portuguese gets its own analyzer, Thai uses Thai word segmentation, Japanese uses
Kuromoji, Korean uses Nori, Simplified Chinese uses Smart Chinese, and Traditional Chinese uses the
dictionary-based ICU analyzer. Chinese script subtags take precedence over regional conventions;
Taiwan, Hong Kong, and Macau default to Traditional Chinese when no script is supplied. Unsupported
languages use a Unicode-aware lowercase and accent-folding fallback. Source and target text are
also indexed through that folded fallback so queries such as `cafe` can match `café`.

## Local instance

Start the existing OpenSearch service without starting MySQL or the application container:

```bash
docker compose -f docker/docker-compose-api-worker.yml up -d --build opensearch
```

OpenSearch is published only on `127.0.0.1:9200`, uses a named persistent volume, and disables its
security plugin for local development only. Its custom image installs the official ICU, Kuromoji,
Nori, and Smart Chinese analysis plugins. Check the running instance with:

```bash
curl http://127.0.0.1:9200/_cluster/health
```

For a locally running Mojito application using the `npm` profile, add:

```properties
l10n.search-index.enabled=true
l10n.search-index.base-url=http://127.0.0.1:9200
l10n.search-index.index-name=tm-text-unit-variants-v1
```

Both complete Docker Compose application stacks enable the search index automatically and connect
through `http://opensearch:9200`. Other deployments remain disabled unless explicitly configured.

## Admin dashboard and indexing

Open **Search index** in the account menu, or visit
`/monitoring/search-index`. The admin-only dashboard shows cluster reachability, cluster health,
index existence, and document count.

**Bootstrap index** creates the configured index and mapping without deleting or replacing an
existing index. New single-node local indexes use zero replicas so their health can become green.

**Reindex** walks translation-memory variants in ascending variant-ID batches, optionally scoped
to one or more repositories, and upserts documents with Elasticsearch-compatible bulk requests.
The request immediately schedules a durable Quartz pollable task; the dashboard can reconnect to
an active task and shows its total, scanned, indexed, and failed document counts as batches finish.
Overlapping requests reuse the existing active task. Page size and bulk size control batch sizes,
not the total number of variants indexed.

**Fuzzy search** searches names, source and target translations, comments, repository names, and
asset paths using each text's own source or target language analyzer. Results can be filtered by
one or more repositories, one or more target locales, and current-variant status; regional locale
tags and case variants are normalized for filtering. Empty repository and locale selections include
all available values.

The `/api/monitoring/search-index/**` endpoints inherit Mojito's existing admin-only monitoring
security rule. Managed staging/production provisioning must include the four required analysis
plugins. Authenticated remote access, incremental synchronization, deletion propagation, and
embedding generation remain follow-up work.
