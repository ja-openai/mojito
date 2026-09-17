# Content demo

This small demo contains two MDX pages, six shared/nested modules, and one MF2
catalogue. English is the source language; demonstration translations are included
for French (`fr`), German (`de`), Spanish (`es`), Japanese (`ja`), and Arabic (`ar`).
The catalogue includes plural selection and date formatting. These fixtures are
sample translations, not completed review decisions.

`demo-create` has already created the repository, pushed every source asset, and
imported the sample translations. In Mojito, open **Content**, choose your demo
repository and a target language, then open `index.mdx`. Choose **Edit translations**
and click a passage, or double-click it. The preview dropdown switches the included
audience module. Shared modules keep the same translation across their occurrences.
The Content tab is an admin opt-in under **My Settings → Admin features**; the URL
printed by the CLI also works for admins with the tab hidden.

For review decisions, create a normal Review Project containing all nine assets
and select **Preview**. No Review Project is created automatically by this command.

## Push and pull

Run these commands in this generated directory, replacing `ContentDemo` with the
repository name you passed to `demo-create`:

```sh
mojito push -r ContentDemo -s content -ft MDX JSON
mkdir -p localized
mojito pull -r ContentDemo -s content -t localized -ft MDX JSON
```

Push both formats together to retain all source assets. Use this dedicated demo
repository; an ordinary push removes assets omitted from its source directory.
Pull reconstructs the localized MDX and MF2 catalogues from saved translations.
The `translations/` directory contains the original fixtures; your edits live in
Mojito and appear in `localized/` after pull.

The server needs external blob storage for `asset-content` and `pollable-task`.
The CLI uses its usual configured host and authentication and does not start a
server or change its storage configuration. If a step fails, completed operations
and generated files remain available for inspection and the command exits nonzero.

For the Vite website build, see `examples/mdx-content-site` in the Mojito source
repository. That separate publishing example renders English and French. The CLI
demo needs no Node.js, website build, or model service at runtime.
