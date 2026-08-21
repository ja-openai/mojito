# Native Rust localization CLI

## Scope

The native CLI replaces the Java process only for `push`, `pull`, and
localized-asset `import`. It does not replace the Mojito server, other Java CLI
commands, or unsupported XLIFF/translation-kit workflows.

The crate is `cli/rust/mojito-cli`. It links directly to
`file-formats/rust/mojito-file-formats`, produces an executable named `mojito`,
and contains no Java, Spring, or Okapi dependency.

This is product code, so it belongs beside the existing CLI rather than under a
`sandbox/` or temporary directory. The standalone crate boundary keeps its
dependencies and packaging opt-in. A branch plus worktree provides the safe
experimentation boundary without making the implementation easy to lose or
implying that it is disposable.

## Compatibility contract

Existing automation remains the contract:

- Preserve `push`/`p`, `pull`/`l`, and `import` command names.
- Preserve Java option spellings, including `-r`, `-s`, `-t`, `-ft`, `-fo`,
  `-lm`, `-lmt`, `-am`, `-b`, `-bc`, `-bn`, and the existing long options.
- Preserve Java's variable-arity filter/file-type/directory options.
- Preserve repository locale mapping, `MAP_ONLY` versus `WITH_REPOSITORY`,
  parent-before-child imports, branch and asset mapping, push-run/pull-run
  tracking, translation/inheritance statuses, and `--continue-on-error`.
- Preserve source-file discovery, directory include/exclude wildcard behavior,
  source-path regex filtering, Java/Android/gettext/Apple/JSON naming, and
  UTF-8/UTF-16 byte-order marks.
- Use the existing `/api/repositories`, `/api/assets`,
  `/api/pollableTasks`, and `/api/commits/pushRun` contracts without new backend
  endpoints or server-side state.
- Keep normal push waiting and unused-asset deletion, `NO_DELETE`, and
  `SEND_ASSET_NO_WAIT_NO_DELETE` behavior explicit.
- Keep the existing parallel localization endpoint and nested task-output
  protocol; synchronous and asynchronous pull modes remain available.
- Preserve source-less pull selection, including named branches and the null
  branch, and the explicit portable JSON-comment migration marker.

The native CLI validates each source or imported target with the Rust converter
and adds `mojito.converter=portable` to every backend filter-options list.
Backend extraction and generation therefore follow the already-reviewed
portable converter path instead of silently falling back to Okapi.

`XLIFF` and `XCODE_XLIFF` are rejected with `UNSUPPORTED_PORTABLE_FORMAT`; they
remain Java-only until a reviewed bilingual file contract exists. Because
XLIFF is one of the Java scanner defaults, the native default scan fails closed
when an in-scope `.xliff` file exists. It never lets normal push cleanup infer
that an unsupported source was removed.

## Configuration without JVM properties

Replicating Spring's entire configuration engine or arbitrary JVM `-D...`
properties would add complexity unrelated to the three native workflows. The
native client instead keeps the useful existing inputs:

1. Brew/system CLI `application.properties`.
2. User CLI `application.properties` under `$HOME/.l10n/config/cli/`.
3. Current-directory `application.properties`.
4. A file selected by `--config`, or by `MOJITO_CONFIG` when the CLI option is
   absent.
5. Existing Spring-style `L10N_RESTTEMPLATE_*` environment variables.
6. Explicit `--url`, `--username`, `--password`, `--token`, and `--header`
   overrides.

The selected file is required. A missing explicit file is an error so a push
cannot fall through to a different server profile. `--config` overrides a stale
or inherited `MOJITO_CONFIG` selector.

Properties support comments, Java continuations/escapes, and `${NAME}` or
`${NAME:default}` environment expansion. Authentication supports the existing
session/form-login CSRF flow, Cloudflare/static headers, externally supplied
bearer tokens, and Azure client credentials. Interactive MSAL device/browser
flows are intentionally not reimplemented; provide an existing bearer token
instead. Explicit `STATEFUL`, `STATELESS`, and `HEADER` modes take precedence
over incidental request headers; headers remain additive in every mode.

The CLI rejects `-D...` before network access and reports the supported
configuration alternatives.

## Validation and rollout

Run:

```sh
cargo test --manifest-path cli/rust/mojito-cli/Cargo.toml --locked
cargo clippy --manifest-path cli/rust/mojito-cli/Cargo.toml \
  --all-targets --locked -- -D warnings
python3 file-formats/conformance/verify.py
```

The native tests include local fake-server workflows for real Java-compatible
push, parallel pull, and localized import calls. A source-contract test reads
the Java command definitions and fails when a workflow flag is missing. The
scanner/path tests also read the existing Java `PullCommandTest_IO` datasets,
and the shared converter continues to use the existing Java/Rust/native
conformance corpus. The CLI does not create a second format fixture set.

Roll out by installing the native binary alongside the Java CLI, comparing
generated files and server request payloads for the same repository, and
switching one localization pipeline at a time. Roll back by restoring the Java
binary; the server API and stored data do not change.
