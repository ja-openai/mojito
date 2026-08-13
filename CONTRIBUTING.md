# Contributing to Mojito

Start with the
[contributor quick start](docs/_docs/guides/open-source-contributors.md).
It covers the Java 21 and Maven-wrapper setup, the embedded development database,
the React/Vite frontend, tests, and optional Codex-assisted setup.

## Development workflow

1. Open an issue or discuss substantial changes with the maintainers first.
2. Create a focused branch from the current default branch.
3. Follow the repository conventions in [`AGENTS.md`](AGENTS.md).
4. Add or update tests that cover your change.
5. Run the relevant Java and frontend checks before opening a pull request.
6. Explain the change, its motivation, and its verification in the pull request.

Keep pull requests focused and preserve existing behavior unless the change
intentionally modifies it. Never commit credentials, tokens, or local
configuration.

## License and attribution

Unless explicitly agreed otherwise, contributions are submitted under the
[Apache License, Version 2.0](LICENSE). Preserve existing copyright, license,
and attribution notices.

## Common checks

```sh
./mvnw spotless:apply
./mvnw -Pno-local-config test
```

For frontend changes:

```sh
source webapp/use_local_npm.sh
npm --prefix webapp/frontend run format
npm --prefix webapp/frontend run lint:fix
npm --prefix webapp/frontend run tsc
npm --prefix webapp/frontend run test
```

If a contribution requires additional legal or organizational review, confirm
the current requirements with this repository's maintainers.
