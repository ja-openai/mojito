# Mojito

Mojito is a continuous localization platform for collecting source strings,
managing translations, reviewing localized content, and generating translated
resources.

## Minimal setup

Install a Java 21 JDK, then run:

```sh
git clone https://github.com/ja-openai/mojito.git
cd mojito
./mvnw -Pno-local-config -DskipTests install
java -jar webapp/target/mojito-webapp-*-exec.jar
```

Open [http://localhost:8080/login](http://localhost:8080/login) and sign in with
`admin` / `ChangeMe`.

The Maven wrapper downloads the project's pinned Node.js and npm versions and
builds the frontend. Mojito uses an in-memory database by default; MySQL and
Docker are not required to get started.

For persistent MySQL, OpenSearch, or the complete Docker Compose API/worker
stack, see the optional setup in the
[contributor quick start](docs/_docs/guides/open-source-contributors.md).

For live frontend development, restart the application with
`--l10n.security.authenticationType=HEADER,DATABASE`, then run:

```sh
source webapp/use_local_npm.sh
npm --prefix webapp/frontend run dev
```

The Vite frontend is available at
[http://localhost:5173/](http://localhost:5173/).

The same guide covers development commands, tests, and optional Codex-assisted
setup.

## Project layout

- `webapp`: Spring Boot application, REST API, persistence, and background jobs.
- `webapp/frontend`: React, TypeScript, and Vite frontend.
- `common`: shared Java code.
- `cli`: Mojito command-line client.
- `dev-docs/design`: current architecture and design notes.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md).

## License and attribution

Mojito is licensed under the Apache License, Version 2.0. See
[LICENSE](LICENSE).

Originally developed by Box, Inc. See the
[original Box project](https://github.com/box/mojito) and
[Mojito website](https://www.mojito.global/).

Original upstream copyright: Copyright 2016 Box, Inc. All rights reserved.
