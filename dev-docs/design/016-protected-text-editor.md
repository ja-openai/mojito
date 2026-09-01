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
  - protected platform placeholders such as Android/iOS printf specifiers
  - MF2-like demo placeholders for the generic editor tooling
  - later bidi controls, syntax diagnostics, and source-target structure checks
- Keep a raw-edit path that round-trips through the same canonical string.

## Non-Goals

- Implementing full MF2 behavior inside this generic ProseMirror component;
  production MF2 messages use the dedicated structured editor.
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

## Compact Controls

Production surfaces use the shared editor control bar, not demo-specific
controls:

- **Hidden chars: Auto** is the default. It shows issue-focused hidden-character
  markers only: repeated, leading, trailing, or special whitespace, tabs, line
  breaks, zero-width characters, and controls. Ordinary single spaces stay
  visually quiet.
- **Hidden chars: All** shows every known hidden-character marker, including
  ordinary single spaces, for explicit inspection.
- **Hidden chars: Off** hides display-only hidden-character markers without
  changing token locking, diagnostics, or the raw string.
- **Edit placeholders / Lock placeholders** is an inline action control. The
  default state keeps placeholder atoms active; Edit placeholders pauses
  placeholder atoms and validation so translators can repair over-detected or
  malformed text while still editing the same canonical raw string.
- Locked placeholders share one blue accent treatment across ICU syntax,
  placeholders, HTML tags, and platform placeholders; category differences
  should not look like different interaction states.
- Locked placeholder chips are draggable as atomic units when moving the whole
  placeholder is safe, such as standalone placeholders, HTML/platform
  placeholders, and ICU `#` markers. ICU structural syntax fragments such as
  `} other {` are not independently draggable; dragging any ICU structural
  syntax chip inside a parser-derived plural/select range selects and moves that
  whole plural/select range instead. Same-editor protected moves are handled by
  the editor as explicit range moves, not left to browser serialization, so
  standalone placeholders and whole ICU plural/select ranges preserve their atom
  structure when reordered.
- Direct text or external drops into a locked placeholder target are blocked.
  Same-editor protected-range drops over another locked placeholder resolve to a
  before/after boundary instead of inserting inside the atom. If the target is an
  ICU syntax chip, the boundary is the containing plural/select range, not the
  individual syntax chip. Wrapped-line drops use vertical pointer position before
  horizontal midpoint so dropping just below a neighboring placeholder lands
  after that placeholder.
  During drag-over, valid text targets should show the editor drop cursor
  clearly, while blocked locked-placeholder targets should switch to a blocked
  visual state and suppress the insertion cursor so the user is not invited to
  drop inside an atom.
- Locked placeholders can be deleted as complete selected atoms or as part of a
  selected whole ICU plural/select range. Backspace/Delete also remove a
  standalone placeholder atom when the caret is immediately after/before it.
  Adjacent deletion of ICU structural syntax fragments is blocked with the same
  protected-edit explanation because removing those fragments would corrupt the
  plural/select structure. Partial mutation and replacement with arbitrary
  pasted/typed text stay blocked until the user chooses Edit placeholders.

Workbench also has a view-level **Display** dropdown in the result subbar:

- **Hidden characters: Auto / All / Off** controls both inactive result-row
  rendering and the active editor markers.
- **Placeholder highlights: On / Off** controls placeholder, tag, and ICU marker
  highlighting in inactive result rows. It does not disable active-editor
  protection.
- Malformed-placeholder diagnostics are rendered in inactive rows by the
  lightweight renderer as inline warning marks, not by mounting ProseMirror per
  row. The renderer shares the editor's marker ordering, including showing
  line-break markers before the break so the marker appears at the end of the
  preceding visual line.

The control bar is intentionally compact, wraps in narrow Workbench/review rows,
and keeps passive token counts out of live-region announcements. Only actionable
errors and blocked-edit explanations are announced.

## Placeholder Extraction

Current frontend extraction supports these token kinds:

- `icu-placeholder`: ICU MessageFormat 1 arguments such as `{name}`,
  `{count, number}`, `{date, date}`, and related argument expressions.
- `icu-syntax`: protected ICU plural/select structure outside localizable option
  bodies, such as `{count, plural, one {` and `} other {`.
- `icu-pound`: ICU plural `#` markers inside plural option bodies.
- `html-tag`: HTML/XML-like tags such as `<link>` and `</link>`.
- `platform-placeholder`: common printf/Foundation/Android-style placeholders.
- `mf2-demo`: MF2-like variables/declarations used only by generic editor demos.

The production surfaces should call this user-facing concept “placeholders” in
UI copy. “Token” remains an implementation term for extracted ranges in code and
tests.

### ICU MessageFormat 1

Use `@formatjs/icu-messageformat-parser` in the frontend for ICU placeholder
ranges because it is already available in the frontend and exposes parser
location metadata. Protect argument expressions such as `{name}` and structural
format expressions such as `{count, plural, one {...} other {...}}` as protected
units for the first production slice. MF1 forms are added through a compact
shared editor action so translators can repair common ICU structures without
freeform syntax surgery. Plural messages offer missing CLDR categories plus an
explicit validated exact-value flow for selectors such as `=0`; select messages
only offer the required missing `other` form, not arbitrary select keys. The
compact action label follows the scoped insertion type: plural, select, or a
neutral ICU form label when mixed actions are visible.

This is conservative: ICU structure and placeholder atoms are protected while
the current message is parseable, while transient invalid ICU text is allowed
during typing so users can enter `{`, close `}`, repair syntax, or back out of a
partial edit. During that invalid window, HTML placeholder tags remain protected
and the backend integrity checker is still the final save gate.

### Platform Placeholders

The frontend composite extractor also protects common platform placeholders used
by Android, iOS/Foundation, and printf-style strings, including forms such as:

- `%s`, `%d`, `%@`
- `%1$s`, `%1$@`
- `%lld`, `%ld`
- `%.2f`
- `%#@files@` Apple `.stringsdict` placeholders
- `%(name)s`, `%(count)03d` Python-style named placeholders

Escaped percent signs such as `%%` are intentionally not protected. This
frontend layer is a conservative safety net, not the final repository-specific
truth. Source/target preservation and custom placeholder rules still belong in
the backend integrity checker bridge.

Placeholder extraction should distinguish confident placeholders from suspicious
near-misses. For example, a malformed or adjacent sequence such as `%1ds`,
`%1$`, `%.`, `%#@files`, `%(name`, `%(count)`, or a placeholder glued to
identifier-looking text can contain a valid-looking placeholder prefix but still
be semantically wrong for the source format. The editor should protect only
confident placeholder spans and surface adjacency/malformed cases as diagnostics
or warning marks, not expand the protected atom to a guessed range. Malformed
Apple stringsdict prefixes such as `%#@files` are warning-only until the closing
`@` makes the placeholder confident. Backend integrity checkers should remain the
authoritative source for
repository-specific malformed-placeholder errors. The frontend currently exposes
those suspicious platform sequences through the shared protected-text diagnostic
API and the editor renders them as inline diagnostic marks. These diagnostics are
not controlled by the hidden-character display setting; Hidden chars Off still
shows malformed-placeholder warnings.

### Structured MF2 Editor

The generic protected-editor tooling retains a small `mf2-demo` extractor for
fixtures such as:

- `{$name}`
- `:function`
- `.input ...`
- `.match ...`

That extractor is not the production MF2 path. Workbench and Review Project now
route MF2 messages to the shared `Mf2TranslationEditor`, which uses the MF2
JavaScript parser/model, structured flat selector and variant rows, source/target
contract diagnostics, and a raw CodeMirror repair mode. A source-declared
compatible numeric input can be promoted into target-locale plural rows plus a
wildcard fallback even when the source message is plain rather than a `.match`;
undeclared, non-selector-capable, and annotation-mismatched target selectors are
rejected.

The structured editor reuses the production editor's compact hidden-character
control and marker presentation. Its default guided mode keeps MF2 expressions
and syntax protected while `Hidden chars: Auto / All / Off` changes only
display decorations without replacing editor state or history. Full-document
CodeMirror editing is exposed as **Advanced source**, not as **Edit
placeholders**: unlike the generic editor's scoped escape hatch, it can change
declarations, selectors, keys, and every variant at once.

API-provided source `messageFormat` metadata controls this routing. Explicit
metadata is authoritative in the frontend; strict source-declaration detection
is only a compatibility fallback for older payloads that omit the field. The
current backend derives the metadata from a final `.mf2` source asset extension
or, for mixed and legacy assets, strict source shape. Repository- or
asset-configured MF2 declaration and server-side parse/contract validation on
all mutation paths remain follow-up work.

### Backend Integrity Checker Bridge

Existing backend integrity checker code already contains placeholder knowledge:

- `MessageFormatIntegrityChecker.extractNonLocalizableParts(...)` uses ICU
  `MessagePattern` to replace non-localizable spans with sentinel ids.
- `RegexIntegrityChecker#getPlaceholders(...)` extracts placeholder sets for
  printf, URL, markdown link, Python f-string, and related checkers.

Production integration should add a REST endpoint that returns placeholder
ranges and labels for a source/target pair using the repository's configured
integrity checkers. The frontend extractor is still valuable for instant ICU
and platform-placeholder feedback, but the server should be authoritative for
repository-specific placeholder rules. This generic placeholder-range bridge is
separate from the remaining MF2 backend work: authoritative asset classification
and MF2 parse/contract validation.

## Protection Profile Direction

Production UI should avoid asking translators to choose a parser mode. The
normal path should be a composite, context-aware profile that runs the relevant
extractors for the repository/source format. The prototype can expose parser
modes for debugging, but the production editor should keep this internal:

- context/repository metadata chooses the profile when available
- ICU/html/platform extractors provide instant frontend feedback
- backend integrity checkers provide authoritative repository-specific ranges
- Edit placeholders mode remains the escape hatch when detection is wrong

MF2 is a format-specific exception to this generic profile: when source
metadata says `MF2`, the host uses the dedicated structured editor rather than
asking the translator to select a protection profile.

## Placeholder Edit Escape Hatch

Edit placeholders mode should not remove ProseMirror from the product architecture. It
should be a mode on the component:

- assisted mode: ProseMirror with selected augmentations enabled
- placeholder-editing mode: the same ProseMirror surface with placeholder protection
  disabled, while display-only marks can remain visible

The initial implementation keeps both locked and placeholder-editing states inside
the same component, not a separate product flow.

Edit placeholders mode is available from the same compact editor control bar. It swaps
the surface to placeholder editing, keeps writing the same canonical raw string, and
keeps hidden-character markers and malformed-placeholder diagnostics available so
translators can recover from over-protection or repair syntax directly without losing
spacing, line-break, or warning visibility.

## Workbench And Review Project Integration

The first integrations replace only user-editable translation text areas:

- Workbench active translation cell editor. Non-editing rows use a lightweight
  read-only renderer that shares placeholder and mark extraction/CSS but emits
  plain React spans, not ProseMirror editors or per-row toolbars.
- Review Project translation/decision text editors.
- Text Unit Detail translation editor for target-locale pages such as
  `/text-units/:id?locale=...`.

Read-only source, baseline, and historical fields can remain plain text/textarea
until the editable flow is stable.

The production editor chrome belongs to the editor component, not the demo page:
a compact top or bottom control bar should sit inside the same border as the
text surface. The default bar should stay limited to editing affordances such as
issue-focused text marks and placeholder protection status. Undo and redo stay on standard
keyboard shortcuts and imperative editor handles, not persistent chrome.
Sample pickers, parser-mode selectors, and fixture controls are demo-only and
should stay outside the textarea chrome.

Initial rollout is user opt-in through **My Settings > Translation editor**.
The local preference is scoped to the signed-in Mojito username in the current
browser so account switching does not carry the opt-in across roles. The default
path remains the native textarea. This lets admins and early testers validate the
ProseMirror editor in Workbench and Review Project without adding runtime cost
or editor behavior changes for everyone else.

Workbench MF2 routing follows that generic-editor preference and the Workbench
placeholder-highlights display option. With both enabled, Workbench shows
compact MF2 previews on collapsed rows and replaces them with the structured
editor and active source-form comparison while a row is editing. With either
disabled, Workbench shows the raw MF2 serialization and keeps editing in the
native textarea. Review Project MF2 detail editors keep using the structured
editor, with locale controlled by the host. The shared
focus/blur handle and primary keyboard action preserve each host's save or
accept workflow. Parser and source/target contract errors disable those
interactive actions until repaired.

Interactive validation is bounded before the JavaScript parser runs. Each MF2
source and target is limited to 65,536 UTF-16 code units and 1,024 brace
characters; exceeding either limit produces an editor error without constructing
a model. These fixed browser limits are a defense-in-depth safety envelope, not
authoritative server validation or the shared configurable runtime policy tracked
by `MF2-01`. Workbench therefore does not eagerly parse every loaded MF2 row and
does not offer bulk acceptance for loaded MF2 current variants: each row must be
accepted through its bounded individual validation path. Review Project keeps
invalid MF2 translations from being accepted, while an already rejected row may
still be marked decided because that transition does not accept the invalid
target.

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
  ICU placeholders, the generic MF2 demo token mode, and the dedicated
  structured MF2 flow.
