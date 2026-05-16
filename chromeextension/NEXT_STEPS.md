# Mojito ICT Extension Next Steps

## Current State

- The extension supports the legacy ICT marker format:
  `START metadata MIDDLE visibleText END`.
- The marker payload is currently self-contained, so hover and modal metadata can work even when
  Mojito is not reachable.
- MT preview calls Mojito's real `/api/machine-translation` endpoint through the Vite dev server.
- MT errors can optionally be shown inline from the popup setting.
- The fixture server only serves static fixture files. It no longer returns fake MT data.

## Marker Format Direction

Keep the current wrapper shape while optimizing the payload:

```text
START payload MIDDLE visibleText END
```

Do not remove `END` for the compact fat format. It lets the parser support multiple marked strings
inside one text node without storing visible text length, and it avoids Unicode length ambiguity.

Support three payload modes in the extension parser:

1. Legacy fat
   - Current base64 text payload mapped to invisible tag characters.
   - Full metadata is embedded in the page.
   - Keeps offline/debug behavior.

2. Compact fat
   - Same wrapper, but payload is compact binary mapped directly to invisible tag characters.
   - Still self-contained.
   - Include magic, version, mode, checksum, translation type, locale, and compact ids/names.
   - Prefer numeric ids and varints where available.
   - Keep optional large fields, like stack, behind flags or omit them by default.

3. ID-only
   - Same wrapper, but payload only contains ids/hashes.
   - Requires Mojito resolver API to hydrate modal metadata.
   - Use batch resolving and extension-side cache.
   - Best long-term option for smallest DOM/text footprint.

## Compact Payload Notes

The current path is:

```text
metadata text -> UTF-8 -> base64 -> invisible tag chars
```

A better compact-fat path is:

```text
structured binary fields -> invisible tag chars
```

This avoids base64 overhead and avoids carrying long string fields when an id or small enum is enough.

Suggested binary fields:

```text
magic/version
mode
flags
translationType
locale id or compact locale bytes
textUnitVariantId OR textUnitId/source hash
optional repo/asset/text unit names when self-contained data is required
checksum
```

## Truncation Rules

ICT requires the marker wrapper to remain structurally valid. If text is truncated after
instrumentation, malformed markers should never swallow the rest of a text node.

Parser behavior:

- Valid marker: decode and enrich.
- Missing `MIDDLE` or `END`: treat as malformed.
- Bad checksum: treat as malformed.
- Malformed marker: remove only recognizable invisible marker characters when safe, keep visible text.
- Debug/error mode can count or surface malformed markers.

Longer-term, add a Mojito-aware truncate helper that parses ICT first, truncates only visible text,
then re-emits a valid marker:

```text
START payload MIDDLE truncatedVisibleText END
```

## Resolver API Direction

For ID-only markers, add a batch resolver endpoint instead of per-string requests:

```http
POST /api/ict/metadata/resolve
```

Input should contain compact refs, for example:

```json
{
  "refs": [
    { "textUnitVariantId": 123456 },
    { "textUnitId": 123, "localeId": 45, "sourceHash": "..." }
  ]
}
```

The extension should batch refs discovered during a scan, cache results by ref, and degrade to a
small "metadata unavailable" state if Mojito cannot be reached.

## MT Follow-Ups

- Batch MT_REQUIRED requests instead of one request per string.
- Cache MT responses in extension memory by locale and source text.
- Keep server-side caching useful by avoiding artificial timestamp changes in real strings.
- Keep the optional inline MT error display for local debugging.

## Testing Checklist

- Legacy marker still decodes.
- Compact fat marker decodes once implemented.
- ID-only marker resolves via batch endpoint once implemented.
- Multiple marked strings in one text node still work.
- Malformed/truncated marker leaves visible text safe.
- MT_REQUIRED replacement works from `http://localhost:8123/ict.html`.
- CORS works through Vite on `http://localhost:5173`.
