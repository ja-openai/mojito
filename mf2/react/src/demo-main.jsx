import React, { useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";

import { FunctionRegistry, partsToString } from "@mojito-mf2/core";
import { selectCardinal } from "@mojito-mf2/core/plural-rules";
import {
  BidiIsolate,
  FormattedMessage,
  FormattedMessageBlock,
  MessageProvider,
  MessageParts,
  MessageContractPanel,
  SourceTargetDiagnostics,
  SourceTargetScenarioMatrix,
  TargetInsertionPanel,
  VariantRowPanel,
  createCompiledMessageCatalog,
  createMessageCatalog,
  insertVariantRowSource,
  isolateText,
  messageContractFromModel,
  removeVariantRowSource,
  useMessage,
  useMessageDiagnostics,
  useMessageEntry,
  useMessageFormatter,
  useMessageIds,
} from "./index.js";
import "./styles.css";

const sources = {
  welcome: "Welcome, {$name}!",
  cart: `.input {$count :number}
.match $count
one {{{$count} item in your cart}}
* {{{$count} items in your cart}}`,
  review: `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
female one {{She reviewed {$count} file}}
male * {{He reviewed {$count} files}}
female * {{She reviewed {$count} files}}
* * {{They reviewed {$count} files}}`,
  profile: "Tap {#link href=$url @title=|Profile page|}profile{/link}. {$name :string @kind=person}",
  release: "Feature {#badge label=|Beta| @title=|Preview feature|/}: {$name}",
  custom: "Custom formatter says {$name :upper}.",
};

const catalog = createMessageCatalog(sources);
const compiledCatalog = createCompiledMessageCatalog({
  compiled: {
    type: "message",
    declarations: [],
    pattern: [
      "Compiled catalog entry for ",
      {
        type: "expression",
        arg: { type: "variable", name: "name" },
      },
    ],
  },
});
const customFunctions = FunctionRegistry.defaults().withFunction("upper", (call) => call.value.toLocaleUpperCase(call.locale));

const playgroundSource = `.input {$count :number}
.match $count
zero {{CLDR zero: no files}}
one {{CLDR one: {$count} file}}
two {{CLDR two: {$count} files}}
few {{CLDR few: {$count} files}}
many {{CLDR many: {$count} files}}
* {{CLDR other: {$count} files}}`;

const playgroundTargetSource = `.input {$count :number}
.match $count
zero {{Aucun fichier}}
one {{Un fichier}}
two {{Deux fichiers}}
few {{Quelques fichiers}}
many {{De nombreux fichiers}}
* {{Autres fichiers: {$count}}}`;

const playgroundValues = `{
  "count": 11,
  "url": "/people/jean"
}`;

const simpleSource = "Hello {$name}";
const simpleTargetSource = "Bonjour {$name}";
const simpleValues = `{
  "name": "Jean"
}`;

const customSource = "Custom formatter says {$name :upper}.";
const customTargetSource = "Le formatteur retourne {$name :upper}.";

const customValues = `{
  "name": "mojito"
}`;

const badgeSource = "Feature {#badge label=|Beta| @title=|Preview feature|/}: {$name}";
const badgeTargetSource = "Fonction {#badge label=|Beta| @title=|Preview feature|/} : {$name}";

const badgeValues = `{
  "name": "Mojito MF2"
}`;

const linkSource = `.input {$count :number}
.match $count
one {{Tap {#link href=$url @title=|Profile page|}profile{/link}: {$count} file}}
* {{Tap {#link href=$url @title=|Profile page|}profile{/link}: {$count} files}}`;

const linkTargetSource = `.input {$count :number}
.match $count
one {{Ouvrir {#link href=$url @title=|Profile page|}profil{/link} : {$count} fichier}}
* {{Ouvrir {#link href=$url @title=|Profile page|}profil{/link} : {$count} fichiers}}`;

const reviewSource = `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
female one {{She reviewed {$count} file}}
male * {{He reviewed {$count} files}}
female * {{She reviewed {$count} files}}
* * {{They reviewed {$count} files}}`;

const reviewTargetSource = `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{Il a relu {$count} fichier}}
female one {{Elle a relu {$count} fichier}}
male * {{Il a relu {$count} fichiers}}
female * {{Elle a relu {$count} fichiers}}
* * {{La personne a relu {$count} fichiers}}`;

const reviewValues = `{
  "gender": "female",
  "count": 3,
  "url": "/people/jean"
}`;

const offsetSource = `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* one {{{$name} and {$others_count} other user liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`;

const offsetTargetSource = `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Votre publication n'a aucun like.}}
1 * {{{$name} a aimé votre publication.}}
* one {{{$name} et {$others_count} autre personne ont aimé votre publication.}}
* * {{{$name} et {$others_count} autres personnes ont aimé votre publication.}}`;

const offsetValues = `{
  "name": "Jean",
  "like_count": 2
}`;

const variableOffsetSource = `.input {$like_count :integer}
.input {$hidden_count :integer}
.local $visible_count = {$like_count :offset subtract=$hidden_count}
.match $like_count $visible_count
0 * {{Nobody liked your post.}}
* one {{{$name} and {$visible_count} other visible user liked your post.}}
* * {{{$name} and {$visible_count} other visible users liked your post.}}`;

const variableOffsetTargetSource = `.input {$like_count :integer}
.input {$hidden_count :integer}
.local $visible_count = {$like_count :offset subtract=$hidden_count}
.match $like_count $visible_count
0 * {{Personne n'a aimé votre publication.}}
* one {{{$name} et {$visible_count} autre personne visible ont aimé votre publication.}}
* * {{{$name} et {$visible_count} autres personnes visibles ont aimé votre publication.}}`;

const variableOffsetValues = `{
  "name": "Jean",
  "like_count": 5,
  "hidden_count": 2
}`;

const bidiSource = "what does this mean {$term :string u:dir=rtl}?";
const bidiTargetSource = "Que signifie {$term :string u:dir=rtl} ?";

const bidiValues = `{
  "term": "טעא"
}`;

const singleEditorSource = "Hello {$name}, ChatGPT is made by {$company :string u:dir=ltr}.";
const singleEditorTarget = "Bonjour {$name}, ChatGPT est cree par {$company :string u:dir=ltr}.";
const singleEditorValues = {
  company: "OpenAI",
  name: "Jean",
};

const playgroundComponents = `({
  badge: ({ label, title }) =>
    React.createElement(
      "span",
      {
        className: "live-badge",
        title
      },
      label
    ),
  link: ({ href, title, children }) =>
    React.createElement(
      "a",
      {
        href,
        title,
        className: "live-link",
        onClick: (event) => event.preventDefault()
      },
      children
    )
})`;

function App() {
  const [count, setCount] = useState(2);
  const [gender, setGender] = useState("female");
  const [locale, setLocale] = useState("en");
  const [playSource, setPlaySource] = useState(playgroundSource);
  const [playTargetSource, setPlayTargetSource] = useState(playgroundTargetSource);
  const [playValuesText, setPlayValuesText] = useState(playgroundValues);
  const [playComponentsText, setPlayComponentsText] = useState(playgroundComponents);
  const [playLocale, setPlayLocale] = useState("ar");
  const [playRenderDirection, setPlayRenderDirection] = useState("ltr");
  const [playScenarioFilter, setPlayScenarioFilter] = useState("all");
  const [singleTargetSource, setSingleTargetSource] = useState(singleEditorTarget);
  const [singleMode, setSingleMode] = useState("translation");
  const [singleLocale, setSingleLocale] = useState("fr");
  const [singleRenderDirection, setSingleRenderDirection] = useState("ltr");
  const components = useMemo(
    () => ({
      badge: ({ label, title }) => (
        <span className="live-badge" title={title}>
          {label}
        </span>
      ),
      link: ({ href, title, children }) => (
        <a href={href} title={title} onClick={(event) => event.preventDefault()}>
          {children}
        </a>
      ),
    }),
    [],
  );

  return (
    <MessageProvider catalog={catalog} locale={locale} components={components} functions={customFunctions}>
      <main className="shell">
        <header className="toolbar">
          <div>
            <h1>MF2 React Wrapper Demo</h1>
            <p>Native JS parser/runtime, generated CLDR plurals, and React parts rendering.</p>
          </div>
          <label>
            Locale
            <select value={locale} onChange={(event) => setLocale(event.target.value)}>
              <option value="en">en</option>
              <option value="fr">fr</option>
              <option value="ru">ru</option>
              <option value="ar">ar</option>
            </select>
          </label>
        </header>

        <SingleMessageEditor
          source={singleEditorSource}
          targetSource={singleTargetSource}
          values={singleEditorValues}
          locale={singleLocale}
          renderDirection={singleRenderDirection}
          mode={singleMode}
          components={components}
          functions={customFunctions}
          onTargetSourceChange={setSingleTargetSource}
          onLocaleChange={setSingleLocale}
          onRenderDirectionChange={setSingleRenderDirection}
          onModeChange={setSingleMode}
        />

        <details className="advanced-demo">
          <summary>
            <span>Advanced React wrapper playground</span>
            <small>Open for component demos, source/target diagnostics, row tools, parts inspection, and code snippets.</small>
          </summary>

          <section className="panel">
            <div className="controls">
              <label>
                Count
                <input type="range" min="0" max="12" value={count} onChange={(event) => setCount(Number(event.target.value))} />
                <strong>{count}</strong>
              </label>
              <label>
                Gender
                <select value={gender} onChange={(event) => setGender(event.target.value)}>
                  <option value="female">female</option>
                  <option value="male">male</option>
                  <option value="unknown">unknown</option>
                </select>
              </label>
            </div>
          </section>

          <section className="grid">
            <DemoCard title="String hook">
              <HookMessage />
            </DemoCard>
            <DemoCard title="Plural">
              <FormattedMessage id="cart" values={{ count }} />
            </DemoCard>
            <DemoCard title="Multi-selector">
              <FormattedMessage id="review" values={{ gender, count }} />
            </DemoCard>
            <DemoCard title="Rich text parts">
              <FormattedMessage id="profile" values={{ name: "Jean", url: "/people/jean" }} />
            </DemoCard>
            <DemoCard title="Standalone markup">
              <FormattedMessage id="release" values={{ name: "Mojito MF2" }} />
            </DemoCard>
            <DemoCard title="Custom function">
              <FormattedMessage id="custom" values={{ name: "mojito" }} />
            </DemoCard>
            <DemoCard title="Precompiled model">
              <MessageProvider catalog={compiledCatalog} locale={locale} components={components} functions={customFunctions}>
                <FormattedMessage id="compiled" values={{ name: "Mojito" }} />
              </MessageProvider>
            </DemoCard>
          </section>

          <section className="source-grid">
            {Object.entries(sources).map(([id, source]) => (
              <article key={id}>
                <h2>{id}</h2>
                <pre>{source}</pre>
              </article>
            ))}
          </section>

          <Playground
            source={playSource}
            targetSource={playTargetSource}
            valuesText={playValuesText}
            componentsText={playComponentsText}
            locale={playLocale}
            renderDirection={playRenderDirection}
            scenarioFilter={playScenarioFilter}
            components={components}
            functions={customFunctions}
            onSourceChange={setPlaySource}
            onTargetSourceChange={setPlayTargetSource}
            onValuesChange={setPlayValuesText}
            onComponentsChange={setPlayComponentsText}
            onLocaleChange={setPlayLocale}
            onRenderDirectionChange={setPlayRenderDirection}
            onScenarioFilterChange={setPlayScenarioFilter}
            onSimplePreset={() => {
              setPlaySource(simpleSource);
              setPlayTargetSource(simpleTargetSource);
              setPlayValuesText(simpleValues);
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onCustomPreset={() => {
              setPlaySource(customSource);
              setPlayTargetSource(customTargetSource);
              setPlayValuesText(customValues);
              setPlayLocale("en");
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onLinkPreset={() => {
              setPlaySource(linkSource);
              setPlayTargetSource(linkTargetSource);
              setPlayValuesText(playgroundValues);
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onBadgePreset={() => {
              setPlaySource(badgeSource);
              setPlayTargetSource(badgeTargetSource);
              setPlayValuesText(badgeValues);
              setPlayLocale("en");
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onReviewPreset={() => {
              setPlaySource(reviewSource);
              setPlayTargetSource(reviewTargetSource);
              setPlayValuesText(reviewValues);
              setPlayLocale("en");
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onOffsetPreset={() => {
              setPlaySource(offsetSource);
              setPlayTargetSource(offsetTargetSource);
              setPlayValuesText(offsetValues);
              setPlayLocale("en");
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onVariableOffsetPreset={() => {
              setPlaySource(variableOffsetSource);
              setPlayTargetSource(variableOffsetTargetSource);
              setPlayValuesText(variableOffsetValues);
              setPlayLocale("en");
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onBidiPreset={() => {
              setPlaySource(bidiSource);
              setPlayTargetSource(bidiTargetSource);
              setPlayValuesText(bidiValues);
              setPlayLocale("en");
              setPlayRenderDirection("ltr");
              setPlayScenarioFilter("all");
            }}
            onPluralPreset={() => {
              setPlaySource(playgroundSource);
              setPlayTargetSource(playgroundTargetSource);
              setPlayValuesText(playgroundValues);
              setPlayLocale("ar");
              setPlayRenderDirection("rtl");
              setPlayScenarioFilter("all");
            }}
          />
        </details>
      </main>
    </MessageProvider>
  );
}

function HookMessage() {
  const text = useMessage("welcome", { name: "Mojito" });
  return <>{text}</>;
}

function DemoCard({ title, children }) {
  return (
    <article className="card">
      <h2>{title}</h2>
      <div className="rendered">{children}</div>
    </article>
  );
}

function SingleMessageEditor({
  source,
  targetSource,
  values,
  locale,
  renderDirection,
  mode,
  components,
  functions,
  onTargetSourceChange,
  onLocaleChange,
  onRenderDirectionChange,
  onModeChange,
}) {
  const textareaRef = useRef(null);
  const sourceCatalog = useMemo(() => createMessageCatalog({ source }), [source]);
  const targetCatalog = useMemo(() => createMessageCatalog({ target: targetSource }), [targetSource]);
  const sourceEntry = sourceCatalog.get("source");
  const targetEntry = targetCatalog.get("target");
  const sourceContract = useMemo(() => messageContractFromModel(sourceEntry?.model), [sourceEntry?.model]);
  const targetContract = useMemo(() => messageContractFromModel(targetEntry?.model), [targetEntry?.model]);
  const hasTargetDiagnostics = targetEntry?.diagnostics?.length > 0;
  const insertIntoTarget = (snippet) => {
    const textarea = textareaRef.current;
    const start = textarea?.selectionStart ?? targetSource.length;
    const end = textarea?.selectionEnd ?? start;
    const nextSource = `${targetSource.slice(0, start)}${snippet}${targetSource.slice(end)}`;
    onTargetSourceChange(nextSource);
    requestAnimationFrame(() => {
      textarea?.focus();
      textarea?.setSelectionRange(start + snippet.length, start + snippet.length);
    });
  };

  return (
    <section className="single-editor">
      <div className="single-editor-header">
        <div>
          <h2>Translation editor</h2>
          <p>One target editor first. Raw MF2 mode exposes the underlying source when needed.</p>
        </div>
        <div className="single-editor-controls">
          <div className="code-mode-row" role="group" aria-label="Editor mode">
            {[
              ["translation", "Translate"],
              ["raw", "Raw MF2"],
            ].map(([nextMode, label]) => (
              <button
                type="button"
                key={nextMode}
                aria-pressed={mode === nextMode ? "true" : "false"}
                onClick={() => onModeChange(nextMode)}
              >
                {label}
              </button>
            ))}
          </div>
          <label>
            Target locale
            <select value={locale} onChange={(event) => onLocaleChange(event.target.value)}>
              <option value="en">en</option>
              <option value="fr">fr</option>
              <option value="ar">ar</option>
              <option value="he">he</option>
            </select>
          </label>
          <label>
            Preview dir
            <select value={renderDirection} onChange={(event) => onRenderDirectionChange(event.target.value)}>
              <option value="ltr">ltr</option>
              <option value="rtl">rtl</option>
              <option value="auto">auto</option>
              <option value="locale">locale</option>
            </select>
          </label>
        </div>
      </div>
      <div className="single-editor-grid">
        <div className="single-editor-main">
          <div className="source-reference">
            <h2>Source</h2>
            <p>{source}</p>
          </div>
          <label>
            {mode === "raw" ? "Raw target MF2" : "Target translation"}
            <textarea
              ref={textareaRef}
              className="single-editor-textarea"
              value={targetSource}
              onInput={(event) => onTargetSourceChange(event.currentTarget.value)}
              onChange={(event) => onTargetSourceChange(event.currentTarget.value)}
              spellCheck="false"
            />
          </label>
          {mode === "translation" ? (
            <TargetInsertionPanel contract={sourceContract} targetContract={targetContract} onInsert={insertIntoTarget} />
          ) : null}
        </div>
        <aside className="single-editor-side">
          <h2>Preview</h2>
          <div className="status-line">
            {hasTargetDiagnostics ? `target diagnostics: ${targetEntry.diagnostics.length}` : `target parsed: ${targetSource.length} chars`}
          </div>
          <div className="single-editor-values">
            {Object.entries(values).map(([name, value]) => (
              <span key={name}>
                {name}: <strong>{String(value)}</strong>
              </span>
            ))}
          </div>
          <SourceTargetDiagnostics sourceModel={sourceEntry?.model} targetModel={targetEntry?.model} locale={locale} onInsertFix={insertIntoTarget} />
          <MessageProvider catalog={targetCatalog} locale={locale} components={components} functions={functions}>
            <SingleEditorPreview values={values} renderDirection={renderDirection} showRaw={mode === "raw"} />
          </MessageProvider>
          {mode === "raw" ? <MessageContractPanel contract={sourceContract} /> : null}
        </aside>
      </div>
    </section>
  );
}

function SingleEditorPreview({ values, renderDirection, showRaw }) {
  const diagnostics = useMessageDiagnostics("target");
  const stringOutput = useMessage("target", values, { bidiIsolation: "default" });
  return (
    <>
      {diagnostics.length > 0 ? (
        <div className="error">Parser diagnostics: {diagnostics.map((diagnostic) => diagnostic.code).join(", ")}</div>
      ) : (
        <FormattedMessageBlock
          id="target"
          values={values}
          dir={renderDirection}
          className="rendered single-editor-rendered"
        />
      )}
      <div className="string-preview">
        <h2>String output</h2>
        <code>{visibleControlText(stringOutput)}</code>
      </div>
      {showRaw ? (
        <MessageParts id="target" values={values}>
          {({ parts, diagnostics: partDiagnostics, locale }) => (
            <div className="single-editor-raw">
              <h2>formatToParts</h2>
              <div className="parts-summary">
                <span>{parts.length} part(s)</span>
                <span>locale: {locale}</span>
                <span>diagnostics: {partDiagnostics.length}</span>
              </div>
              <pre>{JSON.stringify(parts, null, 2)}</pre>
            </div>
          )}
        </MessageParts>
      ) : null}
    </>
  );
}

function Playground({
  source,
  targetSource,
  valuesText,
  componentsText,
  locale,
  renderDirection,
  scenarioFilter,
  components,
  functions,
  onSourceChange,
  onTargetSourceChange,
  onValuesChange,
  onComponentsChange,
  onLocaleChange,
  onRenderDirectionChange,
  onScenarioFilterChange,
  onSimplePreset,
  onCustomPreset,
  onLinkPreset,
  onBadgePreset,
  onReviewPreset,
  onOffsetPreset,
  onVariableOffsetPreset,
  onBidiPreset,
  onPluralPreset,
}) {
  const targetTextareaRef = useRef(null);
  const [codeMode, setCodeMode] = useState("component");
  const parsedValues = useMemo(() => parseValues(valuesText), [valuesText]);
  const parsedComponents = useMemo(() => compileComponents(componentsText), [componentsText]);
  const playgroundCatalog = useMemo(() => createMessageCatalog({ playground: source }), [source]);
  const targetCatalog = useMemo(() => createMessageCatalog({ target: targetSource }), [targetSource]);
  const entry = playgroundCatalog.get("playground");
  const targetEntry = targetCatalog.get("target");
  const sourceContract = useMemo(() => messageContractFromModel(entry?.model), [entry?.model]);
  const targetContract = useMemo(() => messageContractFromModel(targetEntry?.model), [targetEntry?.model]);
  const hasDiagnostics = entry?.diagnostics?.length > 0;
  const hasTargetDiagnostics = targetEntry?.diagnostics?.length > 0;
  const code = reactSnippet(targetSource, valuesText, locale, renderDirection, codeMode);
  const activeComponents = parsedComponents.value ?? components;
  const sourceStatus = hasDiagnostics ? `source diagnostics: ${entry.diagnostics.length}` : `source parsed: ${source.length} chars`;
  const targetStatus = hasTargetDiagnostics ? `target diagnostics: ${targetEntry.diagnostics.length}` : `target parsed: ${targetSource.length} chars`;
  const countCategory = parsedValues.value?.count == null ? null : safeSelectCardinal(locale, parsedValues.value.count);
  const insertIntoTarget = (snippet) => {
    const textarea = targetTextareaRef.current;
    const start = textarea?.selectionStart ?? targetSource.length;
    const end = textarea?.selectionEnd ?? start;
    const nextSource = `${targetSource.slice(0, start)}${snippet}${targetSource.slice(end)}`;
    onTargetSourceChange(nextSource);
    requestAnimationFrame(() => {
      textarea?.focus();
      textarea?.setSelectionRange(start + snippet.length, start + snippet.length);
    });
  };
  const appendTargetRow = (row) => {
    const nextSource = insertVariantRowSource(targetSource, row, targetEntry?.model?.selectors?.length ?? 1);
    onTargetSourceChange(nextSource);
    requestAnimationFrame(() => {
      const textarea = targetTextareaRef.current;
      textarea?.focus();
      textarea?.setSelectionRange(nextSource.length, nextSource.length);
    });
  };
  const removeTargetRow = (keys) => {
    const nextSource = removeVariantRowSource(targetSource, keys, targetEntry?.model?.selectors?.length ?? keys?.length ?? 1);
    onTargetSourceChange(nextSource);
    requestAnimationFrame(() => {
      const textarea = targetTextareaRef.current;
      if (!textarea) return;
      const cursor = Math.min(textarea.value.length, nextSource.length);
      textarea.focus();
      textarea.setSelectionRange(cursor, cursor);
    });
  };

  return (
    <section className="playground">
      <div className="playground-header">
        <div>
          <h2>React playground</h2>
          <p>Edit MF2 source and values, then render through the React wrapper.</p>
        </div>
        <div className="preset-actions">
          <button type="button" onClick={onSimplePreset}>
            Simple
          </button>
          <button type="button" onClick={onCustomPreset}>
            Custom function
          </button>
          <button type="button" onClick={onPluralPreset}>
            Arabic plurals
          </button>
          <button type="button" onClick={onLinkPreset}>
            Link + plural
          </button>
          <button type="button" onClick={onBadgePreset}>
            Badge
          </button>
          <button type="button" onClick={onReviewPreset}>
            Gender + plural
          </button>
          <button type="button" onClick={onOffsetPreset}>
            Offset likes
          </button>
          <button type="button" onClick={onVariableOffsetPreset}>
            Variable offset
          </button>
          <button type="button" onClick={onBidiPreset}>
            Bidi span
          </button>
        </div>
        <div className="playground-controls">
          <label>
            Locale
            <select value={locale} onChange={(event) => onLocaleChange(event.target.value)}>
              <option value="en">en</option>
              <option value="fr">fr</option>
              <option value="ru">ru</option>
              <option value="ar">ar</option>
            </select>
          </label>
          <label>
            Render dir
            <select value={renderDirection} onChange={(event) => onRenderDirectionChange(event.target.value)}>
              <option value="ltr">ltr</option>
              <option value="rtl">rtl</option>
              <option value="auto">auto</option>
            </select>
          </label>
        </div>
      </div>
      <div className="playground-grid">
        <label>
          MF2 source
          <textarea
            value={source}
            onInput={(event) => onSourceChange(event.currentTarget.value)}
            onChange={(event) => onSourceChange(event.currentTarget.value)}
            spellCheck="false"
          />
        </label>
        <div className="target-editor-block">
          <label>
            Target MF2
            <textarea
              ref={targetTextareaRef}
              value={targetSource}
              onInput={(event) => onTargetSourceChange(event.currentTarget.value)}
              onChange={(event) => onTargetSourceChange(event.currentTarget.value)}
              spellCheck="false"
            />
          </label>
          <TargetInsertionPanel contract={sourceContract} targetContract={targetContract} onInsert={insertIntoTarget} />
          <VariantRowPanel
            sourceModel={entry?.model}
            targetModel={targetEntry?.model}
            locale={locale}
            values={parsedValues.value}
            functions={functions}
            onAppendRow={appendTargetRow}
            onRemoveRow={removeTargetRow}
          />
        </div>
        <label>
          Values JSON
          <textarea
            value={valuesText}
            onInput={(event) => onValuesChange(event.currentTarget.value)}
            onChange={(event) => onValuesChange(event.currentTarget.value)}
            spellCheck="false"
          />
        </label>
        <label>
          Component map JS
          <textarea
            value={componentsText}
            onInput={(event) => onComponentsChange(event.currentTarget.value)}
            onChange={(event) => onComponentsChange(event.currentTarget.value)}
            spellCheck="false"
          />
        </label>
        <div className="playground-output">
          <h2>Rendered</h2>
          <div className="status-line">{sourceStatus}</div>
          <div className="status-line">{targetStatus}</div>
          {countCategory ? <div className="status-line">CLDR cardinal category for count: {countCategory}</div> : null}
          <MessageContractPanel contract={sourceContract} />
          <SourceTargetDiagnostics sourceModel={entry?.model} targetModel={targetEntry?.model} locale={locale} onInsertFix={insertIntoTarget} />
          <MessageProvider catalog={targetCatalog} locale={locale} components={activeComponents} functions={functions}>
            <PlaygroundQuality
              values={parsedValues.value}
              renderDirection={renderDirection}
              canRender={!parsedValues.error && !parsedComponents.error && !hasTargetDiagnostics}
            />
          </MessageProvider>
          {parsedValues.error ? <div className="error">{parsedValues.error}</div> : null}
          {parsedComponents.error ? <div className="error">{parsedComponents.error}</div> : null}
        </div>
        <div className="playground-code">
          <h2>React code</h2>
          <div className="code-mode-row" role="group" aria-label="React code sample">
            {[
              ["component", "Component"],
              ["hook", "String hook"],
              ["parts", "Parts + rich text"],
            ].map(([mode, label]) => (
              <button
                type="button"
                key={mode}
                aria-pressed={codeMode === mode ? "true" : "false"}
                onClick={() => setCodeMode(mode)}
              >
                {label}
              </button>
            ))}
          </div>
          <pre>{code}</pre>
        </div>
        <SourceTargetScenarioMatrix
          sourceModel={entry?.model}
          targetModel={targetEntry?.model}
          locale={locale}
          values={parsedValues.value}
          functions={functions}
          filter={scenarioFilter}
          onFilterChange={onScenarioFilterChange}
        />
      </div>
    </section>
  );
}

function PlaygroundQuality({ values, renderDirection, canRender }) {
  const entry = useMessageEntry("target");
  const diagnostics = useMessageDiagnostics("target");
  const formatter = useMessageFormatter({ bidiIsolation: "default" });
  const ids = useMessageIds();
  return (
    <>
      <div className="status-line">Hook catalog ids: {ids.join(", ")}</div>
      {diagnostics.length > 0 ? (
        <div className="error">useMessageDiagnostics: {diagnostics.map((diagnostic) => diagnostic.code).join(", ")}</div>
      ) : (
        <div className="status-line">useMessageDiagnostics: no issues for {entry.id}</div>
      )}
      {canRender ? (
        <div className="status-line">useMessageFormatter: {visibleControlText(formatter.formatMessage("target", values))}</div>
      ) : null}
      {canRender ? <PlaygroundResult values={values} renderDirection={renderDirection} /> : null}
    </>
  );
}

function PlaygroundResult({ values, renderDirection }) {
  const stringOutput = useMessage("target", values, { bidiIsolation: "default" });
  return (
    <>
      <FormattedMessageBlock
        id="target"
        values={values}
        dir={renderDirection}
        className="rendered playground-rendered"
      />
      <div className="status-line">React output uses HTML &lt;bdi&gt; isolation; string output below uses Unicode isolate markers.</div>
      <div className="string-preview">
        <h2>String output</h2>
        <code>{visibleControlText(stringOutput)}</code>
      </div>
      <MessageParts id="target" values={values}>
        {({ parts, diagnostics, locale }) => (
          <PartsInspection parts={parts} diagnostics={diagnostics} locale={locale} values={values} />
        )}
      </MessageParts>
    </>
  );
}

function PartsInspection({ parts, diagnostics, locale, values }) {
  const partsOutput = partsToString(parts, "default");
  const expressionParts = parts.filter((part) => part.type === "expression");
  return (
    <>
      <BidiRecipePanel expressionParts={expressionParts} />
      <MarkupPropsPanel parts={parts} values={values} />
      <h2>formatToParts</h2>
      <div className="status-line">MessageParts render prop: locale={locale}, diagnostics={diagnostics.length}</div>
      <div className="parts-summary">
        <span>{parts.length} part(s)</span>
        <span>as string: <code>{visibleControlText(partsOutput)}</code></span>
      </div>
      <div className="parts-table">
        <div className="parts-row parts-title">
          <span>Type</span>
          <span>Value</span>
          <span>Metadata</span>
        </div>
        {parts.map((part, index) => (
          <div className="parts-row" key={`${part.type}-${index}`}>
            <strong>{part.type}</strong>
            <span>{visibleControlText(partDisplayValue(part))}</span>
            <code>{partMetadata(part)}</code>
          </div>
        ))}
      </div>
    </>
  );
}

function MarkupPropsPanel({ parts, values }) {
  const rows = parts
    .filter((part) => part.type === "markup" && (part.kind === "open" || part.kind === "standalone"))
    .map((part, index) => {
      const options = resolvePartPayload(part.options, values);
      const attributes = resolvePartPayload(part.attributes, values);
      return {
        key: `${part.kind}-${part.name}-${index}`,
        label: `${part.kind} #${part.name}`,
        options,
        attributes,
        props: { ...options, ...attributes },
      };
    });
  if (rows.length === 0) return null;
  return (
    <section className="markup-props">
      <h2>Rich component props</h2>
      <p>Resolved options and attributes passed to React component callbacks.</p>
      {rows.map((row) => (
        <div className="markup-prop-row" key={row.key}>
          <strong>{row.label}</strong>
          <span>
            options <code>{jsonPreview(row.options)}</code>
          </span>
          <span>
            attributes <code>{jsonPreview(row.attributes)}</code>
          </span>
          <span>
            props <code>{jsonPreview(row.props)}</code>
          </span>
        </div>
      ))}
    </section>
  );
}

function BidiRecipePanel({ expressionParts }) {
  if (expressionParts.length === 0) return null;
  return (
    <section className="bidi-recipes">
      <h2>Bidi recipes for expression values</h2>
      {expressionParts.map((part, index) => {
        const value = String(part.value ?? "");
        const direction = part.direction ?? "auto";
        return (
          <div className="bidi-recipe-row" key={`${value}-${direction}-${index}`}>
            <span>
              <strong>{visibleControlText(value)}</strong>
              <small>direction: {direction}</small>
            </span>
            <code>{visibleControlText(isolateText(value, direction))}</code>
            <code>{bdiSnippet(value, direction)}</code>
            <BidiIsolate direction={direction} className="bidi-recipe-rendered">
              {value}
            </BidiIsolate>
          </div>
        );
      })}
    </section>
  );
}

function resolvePartPayload(payload, values) {
  const resolved = {};
  for (const [name, value] of Object.entries(payload ?? {})) {
    resolved[name] = resolvePartPayloadValue(value, values);
  }
  return resolved;
}

function resolvePartPayloadValue(value, values) {
  if (value?.type === "variable") return values?.[value.name];
  if (value?.type === "literal") return value.value;
  return value?.value ?? value;
}

function jsonPreview(value) {
  return JSON.stringify(value) ?? "undefined";
}

function bdiSnippet(value, direction) {
  return `<bdi dir="${htmlDirection(direction)}">${escapeHtmlContent(value)}</bdi>`;
}

function htmlDirection(direction) {
  return direction === "ltr" || direction === "rtl" ? direction : "auto";
}

function escapeHtmlContent(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function partDisplayValue(part) {
  if (part.type === "markup") {
    const prefix = part.kind === "close" ? "/" : "#";
    return `${part.kind ?? "markup"} ${prefix}${part.name ?? ""}`;
  }
  if (part.type === "fallback") return part.source ?? "";
  return part.value ?? "";
}

function partMetadata(part) {
  const metadata = { ...part };
  delete metadata.type;
  delete metadata.value;
  delete metadata.source;
  if (Object.keys(metadata).length === 0) return "";
  return JSON.stringify(metadata);
}

function visibleControlText(value) {
  let output = "";
  for (const char of String(value)) {
    output += visibleControlToken(char.codePointAt(0)) ?? char;
  }
  return output;
}

function visibleControlToken(codePoint) {
  const labels = {
    0x200e: "<LRM>",
    0x200f: "<RLM>",
    0x202a: "<LRE>",
    0x202b: "<RLE>",
    0x202c: "<PDF>",
    0x202d: "<LRO>",
    0x202e: "<RLO>",
    0x2066: "<LRI>",
    0x2067: "<RLI>",
    0x2068: "<FSI>",
    0x2069: "<PDI>",
  };
  return labels[codePoint] ?? null;
}

function parseValues(text) {
  try {
    return { value: JSON.parse(text) };
  } catch (error) {
    return { error: `Invalid JSON: ${error.message}` };
  }
}

function compileComponents(source) {
  try {
    const value = Function("React", `"use strict"; return (${source});`)(React);
    if (value == null || typeof value !== "object" || Array.isArray(value)) {
      return { error: "Component map must evaluate to an object." };
    }
    return { value };
  } catch (error) {
    return { error: `Component map error: ${error.message}` };
  }
}

function safeSelectCardinal(locale, value) {
  try {
    return selectCardinal(locale, value);
  } catch {
    return null;
  }
}

function reactSnippet(source, valuesText, locale, renderDirection, mode = "component") {
  const setup = `const catalog = createMessageCatalog({
  target: ${JSON.stringify(source)}
});

const functions = FunctionRegistry.defaults().withFunction(
  "upper",
  (call) => call.value.toLocaleUpperCase(call.locale)
);

const components = {
  badge: ({ label, title }) => (
    <span className="live-badge" title={title}>{label}</span>
  ),
  link: ({ href, title, children }) => (
    <a href={href} title={title}>{children}</a>
  )
};`;
  const providerProps = `catalog={catalog} locale="${locale}" components={components} functions={functions}`;
  if (mode === "hook") {
    return `${setup}

function TargetPreview({ values }) {
  const text = useMessage("target", values, { bidiIsolation: "default" });
  const formatter = useMessageFormatter({ bidiIsolation: "default" });
  return (
    <>
      <p>{text}</p>
      <p>{formatter.formatMessage("target", values)}</p>
    </>
  );
}

<MessageProvider ${providerProps}>
  <TargetPreview values={${valuesText.trim()}} />
</MessageProvider>`;
  }
  if (mode === "parts") {
    return `${setup}

function TargetPartsPreview({ values }) {
  const parts = useMessageParts("target", values);
  const formatter = useMessageFormatter();
  const formatterParts = formatter.formatMessageToParts("target", values);
  const aliasParts = formatter.formatToParts("target", values);
  return (
    <>
      <FormattedMessage id="target" values={values} />
      <pre>{JSON.stringify({ parts, formatterParts, aliasParts }, null, 2)}</pre>
    </>
  );
}

<MessageProvider ${providerProps}>
  <TargetPartsPreview values={${valuesText.trim()}} />
  <MessageParts id="target" values={${valuesText.trim()}}>
    {({ parts, diagnostics, locale }) => (
      <pre>{JSON.stringify({ locale, diagnostics, parts }, null, 2)}</pre>
    )}
  </MessageParts>
</MessageProvider>`;
  }
  return `${setup}

<MessageProvider ${providerProps}>
  <FormattedMessageBlock
    id="target"
    values={${valuesText.trim()}}
    dir="${renderDirection}"
  />
  <FormattedMessage id="target" values={${valuesText.trim()}}>
    {(chunks) => <strong>{chunks}</strong>}
  </FormattedMessage>
</MessageProvider>`;
}

createRoot(document.getElementById("root")).render(<App />);
