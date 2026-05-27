import { useMemo, useState } from "react";

import { Mf2TranslationEditor, type Mf2EditorMode } from "../mf2-editor/Mf2TranslationEditor";
import { sourceLiteralPreview, type EditorDiagnostic } from "../mf2-editor/model";

type DemoRow = {
  args: Record<string, unknown>;
  context: string;
  id: number;
  locale: string;
  path: string;
  source: string;
  status: string;
};

const rows: Array<DemoRow> = [
  {
    args: { count: 2, reviewer: "Mina" },
    context: "Review project list item. Keep the target short enough for a table cell.",
    id: 38192,
    locale: "fr",
    path: "checkout/Header.tsx",
    source: `.input {$count :number}
.input {$reviewer :string}
.match $count
one {{{$reviewer} reviewed {$count} file}}
* {{{$reviewer} reviewed {$count} files}}`,
    status: "Needs review",
  },
  {
    args: { count: 2, count2: 5, country: "France", coupon: "SUMMER", customer: "Mina" },
    context: "Autocomplete stress case: several placeholders start with c so filtering is easy to test.",
    id: 38193,
    locale: "fr",
    path: "checkout/PromotionCell.tsx",
    source: `.input {$count :number}
.input {$count2 :number}
.input {$country :string}
.input {$coupon :string}
.input {$customer :string}
{{{$customer} has {$count} item(s), {$count2} backup item(s), a {$coupon} coupon for {$country}.}}`,
    status: "Autocomplete",
  },
  {
    args: { actor: "Sam", due_date: "Friday", file_count: 5, project: "Billing", reviewer: "Alex" },
    context: "Notification email subject. All placeholders come from the product event.",
    id: 38194,
    locale: "ar",
    path: "notifications/Assignment.tsx",
    source: `.input {$actor :string}
.input {$project :string}
.input {$file_count :number}
.input {$reviewer :string}
.input {$due_date :string}
{{{$actor} assigned {$file_count} files from {$project} to {$reviewer} before {$due_date}.}}`,
    status: "Untranslated",
  },
  {
    args: { case: "dative", number: "singular", usage: "bare" },
    context: "German noun inflection. The source has a wildcard in the middle selector position.",
    id: 38196,
    locale: "de",
    path: "glossary/NounInflection.tsx",
    source: `.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare * singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
* * * {{Schild}}`,
    status: "Wildcard",
  },
  {
    args: { like_count: 3, name: "Mojito", others_count: 2 },
    context: "Social notification. The source uses an offset local for the other-user count.",
    id: 38195,
    locale: "ru",
    path: "social/PostNotice.tsx",
    source: `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* one {{{$name} and {$others_count} other visible user liked your post.}}
* * {{{$name} and {$others_count} other visible users liked your post.}}`,
    status: "Warnings",
  },
];

export function ReviewDemoApp() {
  const [activeId, setActiveId] = useState(rows[0]?.id ?? 0);
  const [health, setHealth] = useState<Record<number, string>>({});
  const [localeById, setLocaleById] = useState<Record<number, string>>({});
  const [modeById, setModeById] = useState<Record<number, Mf2EditorMode>>({});
  const [targetById, setTargetById] = useState<Record<number, string>>({});
  const activeRow = useMemo(() => rows.find((row) => row.id === activeId) ?? rows[0], [activeId]);
  const activeLocale = localeById[activeRow.id] ?? activeRow.locale;
  const activeMode = modeById[activeRow.id] ?? "rich";
  const activeTarget = targetById[activeRow.id] ?? activeRow.source;

  return (
    <main className="tms-shell">
      <section className="review-demo-layout">
        <aside className="panel">
          <div className="panel-header">
            <h2>Project strings</h2>
            <span className="tms-demo-kicker">Component playground</span>
          </div>
          <div className="review-list">
            {rows.map((row) => (
              <button
                aria-current={row.id === activeRow.id ? "true" : undefined}
                className="demo-row-card"
                key={row.id}
                onClick={() => setActiveId(row.id)}
                type="button"
              >
                <span>
                  <strong>{sourceLiteralPreview(row.source)}</strong>
                  <small>{row.path}</small>
                </span>
                <em className="demo-row-health">{health[row.id] ?? row.status}</em>
              </button>
            ))}
          </div>
        </aside>

        <section className="panel review-detail">
          <div className="review-detail-header">
            <div>
              <h2>Text unit {activeRow.id}</h2>
              <p className="muted">{activeRow.path} / target {activeLocale}</p>
            </div>
            <div className="button-row">
              <button type="button">Reject</button>
              <button className="primary" type="button">Approve</button>
            </div>
          </div>
          <div className="review-source-card">
            <div>
              <span className="eyebrow">Source</span>
              <p>{sourceLiteralPreview(activeRow.source)}</p>
            </div>
            <div>
              <span className="eyebrow">Context</span>
              <small>{activeRow.context}</small>
            </div>
          </div>
          <div className="review-editor-slot">
            <Mf2TranslationEditor
              args={activeRow.args}
              documentKey={String(activeRow.id)}
              locale={activeLocale}
              mode={activeMode}
              onChange={(snapshot) => {
                setHealth((current) => ({
                  ...current,
                  [activeRow.id]: diagnosticHealthLabel(snapshot.diagnostics),
                }));
              }}
              onLocaleChange={(nextLocale) => {
                setLocaleById((current) => ({
                  ...current,
                  [activeRow.id]: nextLocale,
                }));
              }}
              onModeChange={(nextMode) => {
                setModeById((current) => ({
                  ...current,
                  [activeRow.id]: nextMode,
                }));
              }}
              onTargetChange={(nextTarget) => {
                setTargetById((current) => ({
                  ...current,
                  [activeRow.id]: nextTarget,
                }));
              }}
              showActiveSourceComparison
              showArgumentInputs={false}
              showSource={false}
              source={activeRow.source}
              target={activeTarget}
            />
          </div>
        </section>
      </section>
    </main>
  );
}

function diagnosticHealthLabel(diagnostics: Array<EditorDiagnostic>) {
  const errors = diagnostics.filter((diagnostic) => diagnostic.severity === "error").length;
  const warnings = diagnostics.filter((diagnostic) => diagnostic.severity === "warning").length;
  if (errors && warnings) return `${errors} error${errors === 1 ? "" : "s"}, ${warnings} warning${warnings === 1 ? "" : "s"}`;
  if (errors) return `${errors} error${errors === 1 ? "" : "s"}`;
  if (warnings) return `${warnings} warning${warnings === 1 ? "" : "s"}`;
  return "Clean";
}
