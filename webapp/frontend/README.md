# Mojito Frontend

Overview

- React + Vite + TypeScript app that builds into Spring Boot static resources.
- Served from `/` by the Mojito webapp module.
- Legacy `/n/...` links are redirected to their root-path equivalents.

Dev Environment

- Backend: run Mojito Spring Boot on port 8080.
- Frontend toolchain: source `webapp/use_local_npm.sh` so your shell uses the Maven-managed Node/npm (same versions as CI).
- Start dev server:
  - `cd webapp/frontend`
  - `source ../use_local_npm.sh`
  - `npm install`
  - `npm run dev`
- API proxy: requests to `/api/*` are proxied to `http://localhost:8080`.

Build & Integration

- `npm run build` writes to `webapp/target/classes/public`.
- Spring Boot serves static content from `classpath:/public`, so visit `http://localhost:8080/`.

Maven Integration (packaging)

- The `webapp` Maven module runs an additional `frontend-maven-plugin` execution in `frontend` to build assets during `compile`.
- Result is included automatically in the Spring Boot jar under `public`.

Linguist Time Spent Filters

- The admin report at `/settings/system/linguist-time-spent` has a translator picker searchable by name, username, or numeric user ID. Names appear first, with username and ID underneath.
- Disabled users remain available for historical reports. Select `All translators` to clear the filter.
- `Apply` loads the report for the selected filters; `Recompute` uses the current filter selection.
