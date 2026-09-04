Frontend Routing + Shell Layout
===================================

Context
- The frontend has grown into the main SPA for most Mojito routes.
- The SPA is served from root paths. Old `/n` links are redirected server-side to their root-path equivalents.

Goals
- Use React Router with `BrowserRouter` and root-path routes to align with server forwarding and deep links.
- Introduce a shared shell layout with top navigation and routed content slots.
- Provide initial routes for Repositories and Workbench with sensible defaults/redirects.
- Establish baseline theming tokens and page structure CSS for light/dark support.

Non-goals
- No data fetching, auth, or real page content yet—routes are placeholders.
- No design system or component library decisions; styles are minimal tokens/layout only.
- No 404/500 UX polish beyond redirecting unknown paths.

Plan (narrow)
- Add `react-router-dom@^7` dependency.
- Wrap `App` in `BrowserRouter`, define `Routes`/`Route` tree with a shared `AppLayout` using `Outlet`.
- Provide nav links for `/repositories` and `/workbench`; redirect `/` and fallthrough paths to `/repositories`.
- Add `app.css` with CSS variables (colors, spacing, radius) and shell layout styles, and import it in `App.tsx`.

Stateful dashboard navigation
- Workbench and Review Projects keep their current filters in per-tab `sessionStorage`, addressed by
  the `ws` and `rps` query tokens respectively.
- The shared header retains the latest observed token for each dashboard while navigating among
  header routes, and Review Project subroutes propagate `rps` through their back links.
- Workbench Details is a normal link: regular clicks open in the current tab; modified clicks and
  middle-click retain the browser's native behavior. Leaving an unsaved translation uses the existing
  discard confirmation.
- Before opening Details in the current tab, Workbench records the applied search, result limit,
  sort order, row identity, and the row's viewport offset on its history entry. Both browser Back and
  Details' Back action restore that entry. Restoration waits for the originating virtual row to render, then
  restores its viewport position, focuses its Details link, and briefly highlights the row. This uses
  the row's measured position so variable row heights do not shift the return target. If it no longer
  matches the refreshed results, the saved scroll position is used instead. History restoration does
  not change account defaults.
- Details links include the applied search and sort order in a URL fragment. Opening one in another
  tab offers “Open in Workbench”: it runs the original search in that tab and highlights the text unit.
  It does not depend on the original tab or its session storage. Same-tab navigation continues to use
  its history entry for Back. Links without search context retain the basic Workbench destination.
  Filter-session tokens themselves are not shareable links.

Open questions
- Should navigation highlight active routes or adopt a design-system component?
- Do we want per-route code splitting once pages grow?
- Where should shared tokens live long-term (global CSS vs. theming system)?
- Keep legacy route aliases explicit in `NewFrontendController` and redirect them in the SPA when there is a current replacement route.
