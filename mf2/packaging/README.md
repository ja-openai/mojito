# Standalone package and CI gates

`check_runtime.sh` runs native checks, the shared source/model/format fixtures,
and the direct upstream official-corpus bridge for one runtime. Both portable
and platform registry results use maintained, case-specific dispositions from
`mf2/conformance/official-dispositions/`. Missing disposition files fail the gate.
Go currently has no separate platform formatter, so its CI gate runs only the
portable official corpus. The other seven runtimes also run the shared 47-case
platform adapter corpus. Any reviewed adapter differences are pinned separately
in `mf2/conformance/adapter-dispositions/<runtime>-platform.json`.

```sh
sh mf2/packaging/check_runtime.sh python
sh mf2/packaging/check_runtime.sh shared
```

The same selector powers `.github/workflows/mf2.yml`: eight independent runtime
jobs and a shared CLDR/fixture gate. Java and Kotlin also run their ICU4J adapter
checks; Rust enables ICU4X; PHP exercises Intl; Swift exercises Foundation.
Python CI installs Babel and mypy. JavaScript CI uses Node 22, matching the package
engine requirement. JVM jobs use Java 21, Swift uses macOS with Swift 6, Go uses
the version in `go.mod`, and PHP uses 8.3 with Intl and Composer. These checks
run against the maintained package versions; no release versions are rewritten.

## Real artifacts and clean consumers

```sh
python3 -m pip install 'setuptools>=77' build wheel
python3 mf2/packaging/smoke.py --runtime python --output /tmp/mf2-packages
python3 mf2/packaging/smoke.py --runtime javascript --output /tmp/mf2-packages
# Supported runtimes: python javascript java kotlin go rust swift php
# --runtime all requires every toolchain; use a new output directory each run.
```

The smoke script copies the package to an isolated staging directory, builds its
actual distributable, verifies exact `LICENSE`, `NOTICE`, and
`UNICODE-LICENSE.txt` contents inside the archive, then runs an external consumer
using the installed or extracted artifact. Consumers check public API formatting
and a Russian plural selection using the bundled CLDR data. JavaScript checks
both the root export and formatter subpath. Python installs its wheel in a fresh
venv and runs the consumer with isolated import handling. PHP installs its ZIP
through Composer and uses the generated autoloader.

| Runtime | Artifact and consumer boundary |
| --- | --- |
| Python | Wheel plus source distribution; clean wheel install without dependencies |
| JavaScript | `npm pack` tarball; offline install into an empty project |
| Java / Kotlin | Maven JAR; external `javac`/`java` consumer using only the JAR and declared dependencies |
| Rust | Verified `cargo package` crate; external consumer of the extracted crate, retaining its dependency lock |
| Swift | Source ZIP; extracted SwiftPM package used by a new dependent executable |
| Go | Module ZIP in a local proxy; download into a new module cache and build an external consumer |
| PHP | Composer distribution ZIP; clean Composer installation and autoloaded consumer |

The Go proxy uses the synthetic local version `v0.0.0-packagesmoke`. The PHP
local distribution descriptor uses `dev-main`. These are local consumer-test
identities, not published tags or registry releases. Swift checks a source
package, not a binary framework. No step publishes an artifact.

Every runtime output directory includes artifacts, command logs, and a JSON
report with source/artifact hashes, sizes, and command/toolchain evidence. The
script fails on missing tools or failed consumers. Use `GO` for a specific Go
binary or `COMPOSER_PHAR` for an existing Composer PHAR. It preserves the normal
SwiftPM sandbox; a host that blocks nested sandbox execution must run the check
with the normal build permissions.

Canonical notices are under `licenses/`. Package roots carry these files so
ordinary ecosystem package commands include them; the smoke script does not
inject missing notices into staged packages. Python uses PEP 639 metadata with
setuptools 77 or newer and declares `Apache-2.0 AND Unicode-3.0` to cover both
the Mojito code and bundled generated CLDR data. The wheel and source-distribution
metadata are checked as well as the included license files.

The Unicode license is copied byte-for-byte from the `cldr-core/LICENSE` file
at the immutable CLDR JSON revision in `mf2/cldr/pinned-ref.txt`. `NOTICE` links
to that same source revision. When updating the data pin, verify its license
and copyright notice, then update the canonical files and all package copies
together.

The local archive gate itself has negative tests for missing or altered notices:

```sh
python3 -m unittest discover -s mf2/packaging -p 'test_*.py'
```
