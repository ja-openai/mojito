# Unicode MessageFormat WG Tests

This directory vendors the Unicode MessageFormat Working Group test suite so
Mojito MF2 checks are stable and do not depend on network access at test time.

- Upstream: https://github.com/unicode-org/message-format-wg
- Vendored commit: `dd86e42e10d1d0c9c4401d0781cdd87ee7166366`
- Vendored paths: `test/` and `LICENSE`

The files under `test/` preserve the upstream layout. Local runners should read
that shape directly and report unsupported/skipped cases explicitly instead of
rewriting these files into Mojito-specific fixtures.
