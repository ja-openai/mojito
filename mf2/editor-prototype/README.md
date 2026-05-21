# MF2 Translation Workbench Prototype

Browser prototype for a compact Mojito translator workbench backed by the Rust
MF2 parser/runtime.

The UI is intentionally plain HTML/CSS/JS so we can iterate on the translation
workflow before choosing CodeMirror, Monaco, React components, or `mf2lsp` Wasm
integration. The parser and preview path are real: the dev server calls
`rust/mf2-prototype`.

Current prototype:

- translator-oriented workbench layout
- Rust-backed parse diagnostics, rendered preview, and formatted parts
- structured variant editing for `.input` + `.match` messages
- mechanical plural template insertion
- locale/argument preview
- visible raw source MF2 and raw target MF2 buffers
- target placeholder checks against the raw source contract
- library comparison lab at `/lib-lab.html`:
  - CodeMirror 6 raw source and raw target MF2 editors
  - CodeMirror lint diagnostics from the Rust parser/runtime
  - ProseMirror WYSIWYG variant editor with protected placeholder chips
  - CLDR plural category buttons with example numbers for the current locale
  - shared Rust parser/runtime preview endpoint

Run:

```sh
npm install --cache /private/tmp/mojito-mf2-npm-cache
npm run build
node mf2/editor-prototype/server.mjs
```

Open:

```text
http://127.0.0.1:8788/
```

Open the editor library comparison:

```text
http://127.0.0.1:8788/lib-lab.html
```

Static hosting still loads the UI, but it falls back to a small JS parser and is
only useful for UI smoke testing. Use the Node server for the real Rust parser
and runtime.

Smoke test:

```sh
node mf2/editor-prototype/smoke-test.mjs
```
