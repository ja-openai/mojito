# Native Mojito CLI

`mojito-cli` is an Okapi-free, native Rust implementation of Mojito's existing
`push`, `pull`, and localized-asset `import` workflows. It uses the shared
`mojito-file-formats` converter, preserves the existing Mojito REST contracts,
and deliberately keeps the Java CLI's established command names and option
spellings.

Build or install it from the repository root:

```sh
cargo build --manifest-path cli/rust/mojito-cli/Cargo.toml --locked
cargo install --path cli/rust/mojito-cli --locked
```

The installed executable is named `mojito`. Existing invocations remain
recognizable:

```sh
mojito push -r my-repository -ft JSON_NOBASENAME \
  -fo noteKeyPattern=description extractAllPairs=false \
      exceptions=defaultMessage removeKeySuffix=/defaultMessage \
  --dir-path-include-patterns lang-mojito

mojito pull -r my-repository -ft JSON_NOBASENAME --parallel \
  --inheritance-mode REMOVE_UNTRANSLATED \
  -fo noteKeyPattern=description extractAllPairs=false \
      exceptions=defaultMessage removeKeySuffix=/defaultMessage \
  --dir-path-include-patterns lang-mojito \
  -lm 'fr-FR:fr,zh-TW:zh-Hant'

mojito pull -r my-repository -ft JSON_NOBASENAME \
  --pull-with-no-source-branches master release \
  --pull-with-no-source-null-branch

mojito import -r my-repository -ft FORMATJS_JSON_NOBASENAME \
  -lm 'fr-FR:fr' --status-equal-target APPROVED
```

## Configuration

The native CLI reads existing Java `application.properties` files, in order:

1. `/usr/local/etc/mojito/cli/application.properties`
2. `$HOME/.l10n/config/cli/application.properties`
3. `./application.properties`
4. `--config <path>`, if provided, otherwise `$MOJITO_CONFIG`

Existing Spring-style `L10N_RESTTEMPLATE_*` environment variables override
properties. Explicit connection flags override both:

```sh
mojito --config ./application.properties pull -r my-repository
mojito --url https://mojito.example.com --token "$MOJITO_TOKEN" pull -r my-repository
mojito --url https://mojito.example.com \
  --header "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
  --header "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
  push -r my-repository
```

`--config` overrides the `MOJITO_CONFIG` selector. The selected file is
required: the CLI stops if it is missing instead of falling back to another
server configuration.

The existing Cloudflare Access variables also work unchanged:

```sh
export L10N_RESTTEMPLATE_AUTHENTICATION_MODE=HEADER
export L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID=...
export L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET=...
```

Supported authentication modes are form-login sessions with CSRF protection,
static headers, an existing bearer token through `--token`/`MOJITO_TOKEN`, and
Azure client-credentials configuration. Interactive Azure device-code and
browser login require an already-issued bearer token. An explicitly configured
authentication mode is not silently replaced by configured headers; those
headers are still added to requests. A deliberate `--token`/`MOJITO_TOKEN`
selects bearer authentication. Without an explicit mode or credentials,
supplying headers selects header authentication.

Arbitrary JVM `-D...` flags are intentionally unsupported. Move configuration
into an existing properties file, `MOJITO_CONFIG`, the corresponding
`L10N_RESTTEMPLATE_*` environment variable, or an explicit connection flag.

## Compatibility boundary

Supported file types include Android XML, Apple `.strings`/`.stringsdict`, Java
properties, JSON/FormatJS/Chrome/i18next/VS Code, gettext, CSV/Magento, RESX,
RESW, XTB, JavaScript, TypeScript, YAML, and HTML. The binary requests the
portable backend converter on every operation and validates source/import files
through the Rust converter before sending them.

Bilingual `XLIFF` and `XCODE_XLIFF` are explicitly rejected because their
segment, inline-code, translation-state, and skeleton contracts remain outside
the portable converter. A default scan fails closed when it encounters an
in-scope XLIFF source, which prevents normal push cleanup from treating that
unsupported asset as deleted. Use the existing Java CLI for those workflows.

The compatibility suite includes Java-style argument parsing, existing
properties/environment precedence, format-specific source and target paths,
UTF-8/UTF-16 BOM preservation, locale mappings and inheritance, exact REST
payloads, source-less pull selectors, legacy JSON-comment migration, parallel
pull polling, import status, and normal push cleanup. It also reads the Java
command definitions during tests so newly added workflow flags cannot drift
silently:

```sh
cargo test --manifest-path cli/rust/mojito-cli/Cargo.toml --locked
cargo clippy --manifest-path cli/rust/mojito-cli/Cargo.toml \
  --all-targets --locked -- -D warnings
```
