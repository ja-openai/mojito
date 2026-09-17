# Email template demo and grouped review

`mojito demo-create -t email` creates a small dedicated repository with two emails
and five target languages. It also copies a complete Vite/React/MDX static email
renderer. See [the runnable example](../../examples/mdx-email/README.md).

Email parts use a convention: `name_subject.mdx`, `name_preheader.mdx`, and
`name_body.mdx` in the same directory. Subject and preheader contain a single line
of plain text. Each part is an ordinary asset with explicit segment IDs. Bodies
may include shared MDX modules. No Repository or Asset schema changes are needed.

Content opens the body, finds its two companions through exact path queries in
the same repository and resolved branch, and fetches their ordinary previews.
These requests use the existing preview query invalidation after a save. No
repository-wide asset scan is required. Missing parts show a warning and the body
remains an ordinary document; request errors expose the normal retry control.

The shared document view combines complete groups using the documents it was
authorized to receive. In a Review Project, all three parts must be included.
Grouping preserves each passage's text-unit/review-row IDs, source mismatch
handling, target status, and editor selection. It changes presentation only and
does not create a separate translation write path or any review decisions.

The build-time adapter reconstructs locale file trees from pulled files, compiles
MDX with Vite SSR, and exports subject.txt, preheader.txt, body.html, and body.txt.
Output has no JavaScript or hydration. React escapes content; subjects/preheaders
reject markup and line breaks. Before compilation, the adapter parses every source and target without executing
it, accepts only Markdown, segment comments and empty imported MDX components,
and requires translations to preserve imports/component order. Executable
expressions, exports, raw HTML, component props and unsafe URL schemes are rejected.
Includes stay within content, with cycles rejected and depth limited to three.
The TMS continues to use its bounded inert MDX renderer. The gallery clearly distinguishes
sample fixtures from pulled translations.

This is a neutral structural email preview, not an exact renderer for arbitrary
email components or a compatibility certification across mail clients. Sending,
recipient scenarios, MF2 personalization, and mail-provider integration are not
part of this demo. Extend those through a project-owned renderer rather than
introducing composition/business logic into Mojito.
