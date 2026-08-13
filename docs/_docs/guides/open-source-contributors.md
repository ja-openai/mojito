---
layout: doc
title: "Contributor Quick Start"
date: 2026-08-07 00:00:00 -0700
categories: guides
permalink: /docs/guides/open-source-contributors/
---

This guide explains how to set up and develop
[Mojito](https://github.com/ja-openai/mojito) using its Java, Spring Boot,
React, and Vite toolchain.

## Minimal setup

The fastest way to run Mojito requires only:

- Git.
- A Java 21 JDK. Verify it with `java -version`.
- Internet access for the initial Maven and npm dependency downloads.

Maven does not need to be installed globally: the repository includes the
`./mvnw` wrapper. You do not need to install Node.js or npm separately: the
first build installs the versions pinned by the project.

MySQL, Docker, Redis, and OpenSearch are **not required**. Mojito starts with an
embedded, in-memory HSQLDB database, and the Maven build provides the frontend
toolchain.

### Install Java 21

On macOS, Homebrew can install Java 21:

```sh
brew install openjdk@21
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Make sure your shell actually selects that JDK before continuing:

```sh
java -version
```

### Clone and build

```sh
git clone https://github.com/ja-openai/mojito.git
cd mojito
./mvnw -Pno-local-config -DskipTests install
```

The first build downloads dependencies, installs the repository-managed Node.js
and npm under `webapp/node`, installs frontend dependencies, and builds both the
Spring Boot application and React frontend.

The `no-local-config` profile prevents Maven tests from inheriting unrelated
configuration under `~/.l10n`. It is useful when a local MySQL configuration
exists but MySQL is not running.

### Start Mojito

From the repository root:

```sh
java -jar webapp/target/mojito-webapp-*-exec.jar
```

Open [http://localhost:8080/login](http://localhost:8080/login) and sign in with:

```text
Username: admin
Password: ChangeMe
```

The packaged application includes the built frontend. Its in-memory database is
reset each time the application stops.

## Optional: persistent MySQL

Use MySQL 8 when you need data to survive application restarts, want to exercise
Flyway migrations, or need behavior closer to a production database. You can use
an existing local MySQL installation or run MySQL in Docker.

### MySQL in Docker

The following command starts MySQL 8, publishes it only on localhost, and keeps
its data in a named Docker volume:

```sh
docker run --name mojito-mysql \
  --publish 127.0.0.1:3306:3306 \
  --env MYSQL_ROOT_PASSWORD=ChangeMe \
  --env MYSQL_DATABASE=mojito \
  --env MYSQL_USER=mojito \
  --env MYSQL_PASSWORD=ChangeMe \
  --volume mojito-mysql-data:/var/lib/mysql \
  --detach mysql:8.0.34 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_bin
```

The `ChangeMe` credentials are examples for a local-only development instance.
Do not reuse them in shared or production environments.

### Existing local MySQL

If MySQL 8 is already running on your machine, create the same database and
local development user:

```sql
CREATE USER IF NOT EXISTS 'mojito'@'localhost' IDENTIFIED BY 'ChangeMe';
CREATE DATABASE IF NOT EXISTS mojito CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
GRANT ALL PRIVILEGES ON mojito.* TO 'mojito'@'localhost';
FLUSH PRIVILEGES;
```

### Configure Mojito to use MySQL

Create the local configuration directory:

```sh
mkdir -p ~/.l10n/config/webapp
```

Then create `~/.l10n/config/webapp/application.properties` with:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/mojito?characterEncoding=UTF-8&useUnicode=true
spring.datasource.username=mojito
spring.datasource.password=ChangeMe
spring.datasource.driverClassName=com.mysql.cj.jdbc.Driver
spring.flyway.enabled=true
spring.flyway.clean-disabled=true
l10n.flyway.clean=false
spring.jpa.defer-datasource-initialization=false
spring.jpa.hibernate.ddl-auto=none
```

Start the packaged application with that additional configuration:

```sh
java -jar webapp/target/mojito-webapp-*-exec.jar \
  --spring.config.additional-location="file:${HOME}/.l10n/config/webapp/"
```

This makes application data persistent while retaining the normal local Quartz
scheduler. Never enable Flyway clean against a database whose contents you need
to preserve.

## Optional: Docker Compose and OpenSearch

For a more production-like local environment, the API/worker Docker Compose
stack builds Mojito inside its Java 21 container, so this path requires Docker
but does not require Java or Maven to be installed on the host. It includes:

- MySQL 8 with a persistent named volume.
- OpenSearch with the analysis plugins used by Mojito.
- The Mojito API and background workers running on Java 21.

Start the complete stack from the repository root:

```sh
docker compose -f docker/docker-compose-api-worker.yml up -d --build
```

The first Docker build downloads its own Java, Maven, npm, and container-image
dependencies, so it is slower than starting an already-built local application.

The application runs at [http://localhost:8080/login](http://localhost:8080/login)
with `admin` / `ChangeMe`. OpenSearch is available only on
[http://127.0.0.1:9200](http://127.0.0.1:9200).

Stop the containers while preserving the named MySQL and OpenSearch volumes:

```sh
docker compose -f docker/docker-compose-api-worker.yml down
```

If you only need OpenSearch alongside an otherwise local Mojito instance, start
that one service:

```sh
docker compose -f docker/docker-compose-api-worker.yml up -d --build opensearch
```

Then enable the search index when starting the local application:

```sh
java -jar webapp/target/mojito-webapp-*-exec.jar \
  --l10n.search-index.enabled=true \
  --l10n.search-index.base-url=http://127.0.0.1:9200
```

The search index is optional. See `dev-docs/design/022-search-index.md` for its
configuration and current limitations. Prefer
`docker/docker-compose-api-worker.yml` for a complete stack; the older
`docker/docker-compose.yml` still uses MySQL 5.7 and an outdated Java image.

## Frontend development with Vite

For live frontend reloads, stop the packaged application if it is already
running and restart it with both local login and development-header
authentication enabled:

```sh
java -jar webapp/target/mojito-webapp-*-exec.jar \
  --l10n.security.authenticationType=HEADER,DATABASE
```

In a second terminal, from the repository root:

```sh
source webapp/use_local_npm.sh
npm --prefix webapp/frontend run dev
```

Open [http://localhost:5173/](http://localhost:5173/). Vite proxies `/api/*` to
the Spring Boot server on port 8080 and authenticates the proxied requests as
the local `admin` user.

The Maven build already installs frontend dependencies. If you need to reinstall
them later, use the repository-managed toolchain:

```sh
source webapp/use_local_npm.sh
npm --prefix webapp/frontend install
```

## Run from source

After the initial build, run the Spring Boot backend directly without rebuilding
the frontend on every start:

```sh
./mvnw -pl webapp -P=-frontend spring-boot:run \
  -Dspring-boot.run.arguments=--l10n.security.authenticationType=HEADER,DATABASE
```

Use the separate Vite command above when developing the frontend.

## Tests and formatting

Run all Java tests with the embedded database:

```sh
./mvnw -Pno-local-config test
```

Run one backend test class:

```sh
./mvnw -pl webapp -Pno-local-config -Dtest=YourTestClass test
```

For frontend changes:

```sh
source webapp/use_local_npm.sh
npm --prefix webapp/frontend run tsc
npm --prefix webapp/frontend run lint
npm --prefix webapp/frontend run test
```

Before submitting Java changes, apply the repository's Google Java Format
configuration:

```sh
./mvnw spotless:apply
```

## Repository map

- `webapp/src/main/java`: Spring Boot application, REST endpoints, services,
  persistence, and scheduled jobs.
- `webapp/frontend/src`: React, TypeScript, and Vite frontend.
- `webapp/src/test/java`: backend tests.
- `webapp/frontend/src`: frontend components and colocated Vitest tests.
- `common`: shared Java functionality.
- `cli`: command-line client.
- `dev-docs/design`: design notes for active product and architecture work.
- `AGENTS.md`: repository conventions and commands for contributors and coding
  assistants.

## Set up with Codex

Open the cloned repository in Codex and use a prompt such as:

```text
Read AGENTS.md and docs/_docs/guides/open-source-contributors.md. Prepare this
checkout for Mojito development. Verify that Java 21 is selected,
run ./mvnw -Pno-local-config -DskipTests install, start the application with its
embedded HSQLDB database, and verify the login page. If I will be doing frontend
work, also enable HEADER,DATABASE authentication and start the Vite development
server. Run a relevant test and tell me which URLs and commands to use. Do not
install MySQL, Docker, Redis, or OpenSearch unless I explicitly ask.
```

Codex can inspect the repository, run the build, diagnose common setup issues,
and start development servers. Depending on local security settings, you may
still need to approve dependency downloads, writes outside the checkout,
package-manager commands, or access to the network. Codex cannot provide missing
repository access, credentials, or administrator permissions.

For a repeatable team workflow, the Codex desktop app also supports
repository-shared local-environment setup scripts and actions. A suitable setup
script for this repository is:

```sh
./mvnw -Pno-local-config -DskipTests install
```

## Troubleshooting

**The build selects the wrong Java version.** Run `java -version` and
`./mvnw -version`; both must report Java 21. Select the correct `JAVA_HOME` in
your shell or IDE, then retry.

**A test unexpectedly connects to MySQL.** Run the command with
`-Pno-local-config` to ignore configuration under `~/.l10n` and use the embedded
HSQLDB defaults.

**Port 8080 or 5173 is occupied.** Stop the existing process or choose another
port. The packaged backend accepts `--server.port=18080`; Vite accepts
`VITE_PORT=15173`. Its API proxy targets port 8080, so update
`webapp/frontend/vite.config.ts` locally if the backend also uses another port.

**`node` or `npm` has the wrong version.** Run the initial Maven build and then
`source webapp/use_local_npm.sh`; the frontend pins Node.js and npm in
`webapp/frontend/package.json`.

**An old guide mentions `npm run start-server`.** Those scripts belonged to the
removed `webapp/package.json`; use the commands in this guide instead.
