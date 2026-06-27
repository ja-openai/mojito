#!/usr/bin/env sh
set -eu

if [ -z "${GOPATH+x}" ] || [ -z "${GOMODCACHE+x}" ] || [ -z "${GOCACHE+x}" ]; then
  base="${TMPDIR:-/private/tmp}"
  base="${base%/}"
  root="$base/mojito-mf2-go-${GO_CACHE_SCOPE:-cache}-$$"
  mkdir -p "$root"

  if [ -z "${GOPATH+x}" ]; then
    export GOPATH="$root/gopath"
  fi
  if [ -z "${GOMODCACHE+x}" ]; then
    export GOMODCACHE="$root/modcache"
  fi
  if [ -z "${GOCACHE+x}" ]; then
    export GOCACHE="$root/build"
  fi
fi

if [ -z "${GOTOOLCHAIN+x}" ]; then
  export GOTOOLCHAIN=local
fi
