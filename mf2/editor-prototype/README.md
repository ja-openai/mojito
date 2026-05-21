# MF2 Editor Prototype

Pure HTML prototype for the Mojito MF2 editor workflow.

It is intentionally dependency-free so we can iterate on mechanics before
choosing CodeMirror, Monaco, React components, or `mf2lsp` Wasm integration.

Current prototype:

- raw MF2 source editing
- structured variant editing for `.input` + `.match` messages
- mechanical plural template insertion
- locale/argument preview
- simple diagnostics
- format-to-parts style preview data for text, expressions, and markup

Run:

```sh
python3 -m http.server 8787 --directory mf2/editor-prototype
```

Open:

```text
http://127.0.0.1:8787/
```

Smoke test:

```sh
node mf2/editor-prototype/smoke-test.mjs
```

