Agent Guide (Codex assistants)
==============================

Purpose
- Document conventions and expectations for assistants working in this repo.
- Make the local Mojito + Codex workflow obvious enough that agents do not need
  to rediscover it on every task.

General Guidance
- Default to minimal, purposeful changes; avoid gratuitous complexity.
- Prefer existing Mojito patterns over new abstractions. Read nearby code before
  editing shared services, controllers, or frontend page patterns.
- Keep docs and tracker entries in sync with work you land. `dev-docs/tracker.md`
  is a live backlog; remove or update stale items instead of adding a "Done"
  section.
- Do not revert user changes. Work with the dirty tree unless explicitly asked
  to clean it up.

Repo Map
- `webapp/`: Spring Boot web application, REST APIs, legacy server-rendered UI,
  Quartz jobs, Flyway migrations, and the Maven-managed Node/npm install.
- `webapp/new-frontend/`: React + Vite + TypeScript frontend. It builds into
  Spring Boot static resources and is the preferred place for new frontend work.
- `cli/`: Mojito CLI.
- `common/`: shared Java code.
- `docs/`: published Mojito documentation.
- `dev-docs/`: working design notes and technical tracker for active planning.

Environment
- Use the repo root for Maven commands.
- Reuse the Maven-managed Node/npm for frontend work:
  `source webapp/use_local_npm.sh`.
- Backend local run is normally from `webapp/`. Existing docs show the baseline
  Mojito app at `http://localhost:8080/login` with `admin/ChangeMe`.
- The new frontend dev server lives in `webapp/new-frontend` and proxies
  `/api/*` to `http://localhost:8080`.
- Current new-frontend routes are served at root paths by the built app; legacy
  `/n/...` links redirect to root-path equivalents.

Common Commands
- Full Java formatting: `mvn spotless:apply`
- Backend/package compile with frontend profile: `mvn -pl webapp -Pfrontend test-compile -DskipTests`
- New frontend setup:
  - `source webapp/use_local_npm.sh`
  - `npm --prefix webapp/new-frontend install`
- New frontend dev server: `npm --prefix webapp/new-frontend run dev`
- New frontend checks:
  - `npm --prefix webapp/new-frontend run format`
  - `npm --prefix webapp/new-frontend run lint:fix`
  - `npm --prefix webapp/new-frontend run tsc`
  - `npm --prefix webapp/new-frontend run test`

Codex Tooling
- Use `rg`/`rg --files` first for search.
- Use the Browser plugin to verify significant local frontend changes when a
  dev server or static build is available.
- Use GitHub skills/tools for PR review, CI triage, and publishing work.
- For glossary, MCP, review automation, or frontend architecture changes, check
  the relevant `dev-docs/design/*.md` note before reshaping behavior.

When you see "finalize commit"
- Review all touched code for the simplest implementation that meets the task; suggest simplifications if possible.
- Update relevant docs (e.g., design notes, README snippets) to reflect the change.
- Update `dev-docs/tracker.md`: add new items or remove items that are done/no longer relevant (no "Done" section).
- Propose a concise commit message that fits the change.

Before committing
- Run `mvn spotless:apply` from the repo root.
- For changes under `webapp/new-frontend`, run `source webapp/use_local_npm.sh` and then:
  - `npm --prefix webapp/new-frontend run format`
  - `npm --prefix webapp/new-frontend run lint:fix`
  - `npm --prefix webapp/new-frontend run tsc`
- Run narrower tests that match the files touched. For new frontend behavior,
  include `npm --prefix webapp/new-frontend run test` unless the change is docs
  only.
- If any warnings or failures remain after the standard checks, report them before committing.

Commit messages
- Match the existing repo style when possible: `feat(scope): ...`, `fix(scope): ...`, `refactor(scope): ...`, `chore(scope): ...`.
