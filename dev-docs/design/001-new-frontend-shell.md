Mojito New Frontend Shell (React + Vite)
=======================================

Context
- We are introducing a new frontend that coexists with the legacy UI.
- It should be production-ready, incrementally extensible, and deploy inside the existing Spring Boot app.

Decisions
- Stack: React + Vite + TypeScript.
- Served path: `/` (Vite `base: '/'`).
- Output path: `webapp/target/classes/public` so Spring Boot serves it from the web root.
- Build integration: `frontend-maven-plugin` in `webapp/pom.xml` runs `npm install` and `npm run build` inside `webapp/new-frontend` during `compile`.
- Dev proxy: Vite proxies `/api/*` to `http://localhost:8080`.
- Server forwarding: `NewFrontendController` forwards root-path SPA routes to `/index.html`.
- Legacy prefix: `NewFrontendController` redirects old `/n` links to their root-path equivalents.
- Legacy routes: `ReactAppController` still serves the old frontend for `/login`, `/auth/callback`, `/project-requests`, `/branches`, `/screenshots-legacy`, `/settings/user-management`, and `/settings/box`.
- Security: root-path SPA routes and legacy `/n` redirects are allowlisted in `WebSecurityJWTConfig` so the SPA can load under stateless JWT mode.

Dev Workflow
- Backend: `cd webapp && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.config.additional-location=optional:file://$HOME/.l10n/config/webapp/ -Dspring.profiles.active=$USER,npm -Duser.timezone=UTC"`
- Frontend: `cd webapp/new-frontend && npm install && npm run dev` (default `http://localhost:5173`).
- Packaged app: `http://localhost:8080/`.

Implementation Notes
- Location: `webapp/new-frontend` with `vite.config.ts` configured to write into `../target/classes/public`.
- Keep all new UI assets under this folder; do not mix with legacy webpack output.
- Vite keeps `emptyOutDir: false` because legacy frontend assets are still served for the remaining old routes and the Chrome ICT extension.
- Ensure `.gitignore` excludes Node artifacts globally (`**/node_modules/`, `**/.vite/`, etc.).

Open Questions / Next Steps
- Decide whether to port, split, or retire the remaining legacy frontend routes.
- Preserve or replace the Chrome ICT extension before removing legacy frontend assets.
- Establish longer-term UI component conventions and theming.
