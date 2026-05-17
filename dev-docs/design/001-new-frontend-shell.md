Mojito Frontend Shell (React + Vite)
=======================================

Context
- The Vite frontend is now the webapp UI and replaces the legacy webpack React bundle.
- It should be production-ready, incrementally extensible, and deploy inside the existing Spring Boot app.

Decisions
- Stack: React + Vite + TypeScript.
- Served path: `/` (Vite `base: '/'`).
- Output path: `webapp/target/classes/public` so Spring Boot serves it from the web root.
- Build integration: `frontend-maven-plugin` in `webapp/pom.xml` runs `npm install` and `npm run build` inside `webapp/frontend` during `compile`.
- Dev proxy: Vite proxies `/api/*` to `http://localhost:8080`.
- Server forwarding: `NewFrontendController` forwards root-path SPA routes to `/index.html`.
- Legacy prefix: `NewFrontendController` redirects old `/n` links to their root-path equivalents.
- Legacy route aliases: `/project-requests`, `/branches`, `/screenshots-legacy`, `/settings/user-management`, and `/settings/box` load the SPA and redirect client-side to current routes.
- Runtime config: `/api/frontend/config` exposes the app config that used to be embedded in the legacy server-rendered template.
- Security: root-path SPA routes and legacy `/n` redirects are allowlisted in `WebSecurityJWTConfig` so the SPA can load under stateless JWT mode.

Dev Workflow
- Backend: `cd webapp && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.config.additional-location=optional:file://$HOME/.l10n/config/webapp/ -Dspring.profiles.active=$USER,npm -Duser.timezone=UTC"`
- Frontend: `cd webapp/frontend && npm install && npm run dev` (default `http://localhost:5173`).
- Packaged app: `http://localhost:8080/`.

Implementation Notes
- Location: `webapp/frontend` with `vite.config.ts` configured to write into `../target/classes/public`.
- Keep all UI assets under this folder.
- Vite uses `emptyOutDir: true`; the legacy webpack output is no longer part of the packaged app.
- Ensure `.gitignore` excludes Node artifacts globally (`**/node_modules/`, `**/.vite/`, etc.).

Open Questions / Next Steps
- Decide whether to rebuild route-specific experiences that were retired with the old frontend, such as the branch search and Box settings pages.
- Establish longer-term UI component conventions and theming.
