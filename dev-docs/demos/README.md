# CMS Demo Artifacts

- `content-cms-authoring-demo.mp4` is the persistent Product copy authoring walkthrough.
  It is captured from the DEV-only `cmsPreview=authoring-demo` seed on a disposable
  frontend Vite server, so it renders the real CMS authoring workspace without auth
  or backend API traffic.
- `content-cms-authoring-demo-frames/` contains the source frames used to build that
  walkthrough.
- The final release-repair frame is captured from the DEV-only
  `cmsPreview=release-repair-refresh-failed` seed so the walkthrough also shows the
  saved-repair retry state without mutating backend data.
- Keep only this author-first walkthrough in the demo directory; stale `cms-ui-demo*`
  failed-prototype captures are intentionally removed so they cannot be mistaken for
  the current CMS surface.
- Regenerate with Vite on an unused disposable frontend port such as `9876`; do not
  use a developer's occupied Mojito backend port for this preview.
