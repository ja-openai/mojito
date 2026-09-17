#!/usr/bin/env bash
set -euo pipefail

example_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(cd "$example_dir/../.." && pwd)"

cd "$repo_dir"
mvn -P 'no-local-config,!frontend' -pl cli -am test \
  -Dtest=MdxWebsiteExampleTest -Dsurefire.failIfNoSpecifiedTests=false

cd "$example_dir"
npm run content:prepare -- --translations "$repo_dir/cli/target/mdx-content-site/localized"
npm run build:site
