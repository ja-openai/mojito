# MDX email demo

Two neutral Commonplace emails, **welcome** and **weekly digest**, in English,
French, German, Spanish, Japanese, and Arabic. Each email has separate subject,
preheader (inbox preview text), and body assets. Both bodies reuse the same header
and footer modules. Sample translations are fixtures, not review decisions.

## Create on your Mojito server

Use a Java CLI and server built from this checkout, with external blob storage
configured for `asset-content` and `pollable-task`:

```sh
mojito demo-create -t email -n EmailDemo -o ./email-demo
cd email-demo
npm ci
```

The command creates a dedicated repository, pushes eight MDX assets, imports five
target languages, and copies this complete renderer. Node is needed to build the
email output, not to create the repository. The usual CLI authentication applies.
Existing repository names and nonempty output directories are rejected.

## Five-minute walkthrough

1. **Open an email (one minute).** Open Content, select EmailDemo and French, then
   open `welcome_body.mdx`. Mojito groups its subject, preheader and body into an
   email reading view. Admins can enable the Content tab in My Settings.
2. **Edit in context (one minute).** Enable Edit translations. Click the subject
   or a body passage, inspect its source, edit and save. The underlying asset and
   text-unit identity stay intact. Editing the footer affects both templates.
3. **Review (one minute).** Create a normal French Review Project with all eight
   assets and All statuses. Open Preview. Subject, preheader and body use the same
   grouped view and ordinary review decisions. Include all three parts; an
   incomplete group stays in document view. Saving a translation alone does not
   manufacture a review decision.
4. **Pull and render (one minute).** Run the commands below. Inspect the French
   subject and both email bodies. The exported files contain the saved changes.
5. **Show reuse (one minute).** Switch email and language. Arabic output uses RTL.
   Download the subject, preheader, HTML body, or plain-text body separately.

```sh
export MOJITO_BIN=/absolute/path/to/your/configured/mojito
export MOJITO_REPO=EmailDemo
bash scripts/mojito.sh pull
npm run build
npm run preview
```

Open <http://127.0.0.1:5192/>. Nothing is sent. Output is pure static HTML with
inline styles and presentation tables; there is no hydration or email JavaScript.
The preview gallery is separate from the exported message.

```text
content/
  welcome_subject.mdx
  welcome_preheader.mdx
  welcome_body.mdx
  digest_subject.mdx
  digest_preheader.mdx
  digest_body.mdx
  modules/Header.mdx
  modules/Footer.mdx

dist/fr/welcome/
  subject.txt
  preheader.txt
  body.html
  body.txt
  preview.html
```

Subject and preheader are single-line plain text inside MDX files, with explicit
`mojito-id` comments. The body is ordinary supported MDX, with relative module
imports. The renderer accepts Markdown and empty imported components only; it
rejects executable expressions, raw HTML, component props, and translated
composition changes before compilation. Module depth is limited to three, with
cycles rejected. To demonstrate authoring, edit a source file under `content/`, run
`bash scripts/mojito.sh push`, translate/review in Mojito, then pull and build.
Push the entire dedicated source directory so omitted assets are not retired.
Do not reimport `translations/` after making human corrections; it is seed data.

## Local sample and checks

Without a Mojito server, use `npm ci && npm run build:sample && npm run preview`.
The gallery explicitly labels sample output. Normal `npm run build` requires
`localized/` from pull and never falls back to fixtures. `npm test` checks complete
exports, missing targets, shared-module corrections, and plain-text field rules.

Mojito groups files by the exact `_subject.mdx`, `_preheader.mdx`, `_body.mdx`
convention within the same repository and branch. It uses its bounded document
renderer and never executes email components. The build uses Vite/React/MDX on
trusted project files; layout and composition remain in Git. The Mojito email
view approximates structure, not a mail client's exact rendering. This example
does not cover client compatibility certification, sending, recipient data, MF2
personalization, or deliverability. Those belong to an application's mail adapter.
