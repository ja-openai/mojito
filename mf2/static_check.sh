#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

GO_CACHE_SCOPE=static . ./go_cache_env.sh

section() {
  printf '\n== %s ==\n' "$1"
}

section "Rust static checks"
(
  cd rust/mojito-mf2
  hand_written_rust_files="$(find src tests examples -name '*.rs' ! -name 'lib.rs' ! -name 'cldr_plural_rules.rs' -print)"
  rustfmt --check $hand_written_rust_files
  cargo check --all-targets
)

section "Swift static checks"
(cd swift/MessageFormat2 && sh run.sh build)

section "Python static checks"
(cd python && python3 -m compileall -q src tests tools examples)

section "Java static checks"
(cd java && sh run.sh --prepare-only)

section "Kotlin static checks"
(cd kotlin && sh run.sh --prepare-only)

section "JavaScript static checks"
(cd javascript && npm run check:types)

section "Go static checks"
(
  cd go
  unformatted="$(gofmt -l .)"
  if [ -n "$unformatted" ]; then
    printf 'gofmt is required for:\n%s\n' "$unformatted" >&2
    exit 1
  fi
  env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" \
    GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" \
    GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" \
    GOTOOLCHAIN="${GOTOOLCHAIN:-local}" \
    go vet ./...
)

section "PHP static checks"
(cd php && find src tests examples -name '*.php' -print -exec php -l {} \;)
