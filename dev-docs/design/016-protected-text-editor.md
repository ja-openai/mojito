# Protected Text Editor

## Purpose

Provide a production-grade replacement for Mojito translation text areas that can
show issue-focused invisible text marks, protect placeholders from accidental
editing, and still preserve a plain raw string as the canonical value.

This starts with issue I18N-103: toggle visible invisible spaces and line
breaks. Placeholder protection is the next layer on the same editor foundation.

## Goals

- Keep `value: string` and `onChange(value: string)` as the public React API so
  the editor is swappable with existing text areas.
- Use ProseMirror as the web editing engine for selection, history, decorations,
  copy/paste serialization, and protected inline atoms.
- Support progressive augmentations:
  - visible spaces and line breaks
  - protected ICU MessageFormat 1 placeholders
  - demo MF2 placeholders
  - later bidi controls, syntax diagnostics, and source-target structure checks
- Keep a raw-edit path that round-trips through the same canonical string.

## Non-Goals

- Full MF2 editor behavior in this pass.
- Replacing backend integrity checks with frontend checks.
- Building a mobile editor implementation in the web repo.

## Architecture

```text
raw string
  -> placeholder extractor / invisible-character analyzer
  -> ProseMirror doc
  -> decorations + protected inline atoms
  -> user edits
  -> serialize ProseMirror doc
  -> raw string
```

The raw string is authoritative. ProseMirror is the editing presentation and
transaction layer, not a new storage format.

## Web Model

Use two different mechanisms intentionally:

- **Decorations** for display-only augmentation. Normal single spaces stay
  quiet by default; repeated, leading, trailing, special spaces, tabs, line
  breaks, zero-width characters, and warnings remain normal text and can be
  edited as text.
- **Inline atom nodes** for protected placeholders. A placeholder atom serializes
  to its original raw placeholder text, renders as a compact chip, and cannot be
  edited internally.
- **Inline hard-break nodes** for line breaks. The raw string still serializes as
  `\n` or the original line-break sequence, but ProseMirror does not keep
  literal newline characters inside text nodes.

The editor can rebuild its ProseMirror document from the current raw string when
augmentation settings change. This is acceptable because the raw string remains
canonical and the component is a focused text editing surface.

## Placeholder Extraction

### ICU MessageFormat 1

Use `@formatjs/icu-messageformat-parser` in the frontend for ICU placeholder
ranges because it is already available in the frontend and exposes parser
location metadata. Protect argument expressions such as `{name}` and structural
format expressions such as `{count, plural, one {...} other {...}}` as protected
units for the first production slice. Plural forms are added through a compact
shared editor action so translators can add missing MF1 forms without freeform
syntax surgery; the action offers named CLDR plural categories plus an explicit
validated exact-value flow for selectors such as `=0`.

This is conservative: ICU structure and placeholder atoms are protected while
the current message is parseable, while transient invalid ICU text is allowed
during typing so users can enter `{`, close `}`, repair syntax, or back out of a
partial edit. During that invalid window, HTML placeholder tags remain protected
and the backend integrity checker is still the final save gate.

### MF2 Demo

Use a small demo extractor for MF2-like placeholders and declarations:

- `{$name}`
- `:function`
- `.input ...`
- `.match ...`

This is demo-only until an MF2 parser with stable source spans is available.

### Backend Integrity Checker Bridge

Existing backend integrity checker code already contains placeholder knowledge:

- `MessageFormatIntegrityChecker.extractNonLocalizableParts(...)` uses ICU
  `MessagePattern` to replace non-localizable spans with sentinel ids.
- `RegexIntegrityChecker#getPlaceholders(...)` extracts placeholder sets for
  printf, URL, markdown link, Python f-string, and related checkers.

Production integration should add a REST endpoint that returns placeholder
ranges and labels for a source/target pair using the repository's configured
integrity checkers. The frontend extractor is still valuable for instant ICU
feedback, but the server should be authoritative for repository-specific
placeholder rules.

## Unprotected Edit

Unprotected mode should not remove ProseMirror from the product architecture. It should
be a mode on the component:

- assisted mode: ProseMirror with selected augmentations enabled
- unprotected mode: the same ProseMirror surface with placeholder protection
  disabled, while display-only marks can remain visible

The initial implementation keeps both protected and unprotected editing inside
the same component, not a separate product flow.

Unprotected mode is available from the same compact editor control bar. It swaps the
surface to unprotected editing, keeps writing the same canonical raw string, and
keeps visible marks available so translators can recover from over-protection or
repair syntax directly without losing spacing/line-break visibility.

## Workbench And Review Project Integration

The first integrations should replace only user-editable translation text areas:

- Workbench active translation cell editor. Non-editing rows stay on the plain
  read-only textarea path so the result list does not become a ProseMirror list.
- Review Project translation/decision text editors.
- Text Unit Detail translation editor for target-locale pages such as
  `/text-units/:id?locale=...`.

Read-only source, baseline, and historical fields can remain plain text/textarea
until the editable flow is stable.

The production editor chrome belongs to the editor component, not the demo page:
a compact top or bottom control bar should sit inside the same border as the
text surface. The default bar should stay limited to editing affordances such as
issue-focused text marks and protected-token status. Undo and redo stay on standard
keyboard shortcuts and imperative editor handles, not persistent chrome.
Sample pickers, parser-mode selectors, and fixture controls are demo-only and
should stay outside the textarea chrome.

Initial rollout is user opt-in through **My Settings > Translation editor**.
The default path remains the native textarea. This lets admins and early testers
validate the ProseMirror editor in Workbench and Review Project without adding
runtime cost or editor behavior changes for everyone else.

## Mobile Direction

For iOS and Android, ProseMirror is not the runtime. The portable pieces should
be:

- placeholder extraction contract: raw range, label, kind, severity
- invisible-character analyzer: code point to marker metadata
- serialization contract: protected token serializes to raw text
- raw string remains canonical

Native implementations can use platform text spans:

- iOS: `UITextView` / `NSTextStorage` attributes for visible markers and
  placeholder token ranges, with delegate checks preventing edits inside
  protected ranges.
- Android: `EditText` / `Spannable` spans for markers and protected ranges,
  with `InputFilter` / `TextWatcher` enforcement.

The web component should not leak ProseMirror-specific data into APIs used by
mobile clients.

## Production Gates

- Raw string round-trips exactly through editing, toggling, copy, paste, and
  undo/redo.
- Placeholder chips serialize to original raw text.
- Editing inside protected placeholders is impossible through typing,
  paste, delete, and IME input.
- Toggle changes do not mutate raw text.
- Workbench and Review Project save flows receive the exact raw string.
- Browser verification covers LTR, RTL, line breaks, repeated spaces, NBSP,
  ICU placeholders, and MF2 demo placeholders.
