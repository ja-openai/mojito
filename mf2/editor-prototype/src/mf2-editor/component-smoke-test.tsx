import type { ComponentProps } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { Mf2TranslationEditor } from "./Mf2TranslationEditor";
import {
  mf2ProseMirrorDocFromPattern,
  mf2ProseMirrorPatternFromDoc,
} from "./Mf2ProseMirrorEditor";

const proseMirrorDoc = mf2ProseMirrorDocFromPattern("Due {$due :datetime dateStyle=short} for {$name}");
assertIncludes(
  mf2ProseMirrorPatternFromDoc(proseMirrorDoc),
  "{$due :datetime dateStyle=short}",
);
assertIncludes(
  mf2ProseMirrorPatternFromDoc(proseMirrorDoc),
  "{$name}",
);

const validRichMarkup = renderEditor();
assertAllIncludes(validRichMarkup, ['data-mf2-editor="prosemirror"', 'data-readonly="false"', "mf2-shortcuts", "Restore"]);

const defaultLocaleMarkup = renderToStaticMarkup(
  <Mf2TranslationEditor
    source="Hello {$name}"
    target="Bonjour {$name}"
  />,
);
assertAllIncludes(defaultLocaleMarkup, ['data-target-direction="ltr"', '<option value="en" selected="">English</option>']);

const readOnlyRichMarkup = renderEditor({ readOnly: true });
assertAllIncludes(readOnlyRichMarkup, [
  'data-mf2-editor="prosemirror"',
  'data-readonly="true"',
  "missing-source-placeholder",
  "<span>Target language</span>",
  '<button type="button" aria-pressed="true">Edit</button>',
  '<button type="button" aria-pressed="false">Raw</button>',
]);
assertAllNotIncludes(readOnlyRichMarkup, ["mf2-shortcuts", "Restore"]);

const invalidRichMarkup = renderEditor({ target: "Bonjour {" });
assertAllIncludes(invalidRichMarkup, ['data-mf2-editor="prosemirror"', 'data-readonly="true"', "unclosed-placeholder"]);
assertAllNotIncludes(invalidRichMarkup, ["mf2-shortcuts", "Restore"]);

const invalidSourceFallbackMarkup = renderEditor({
  source: "Current source {$fresh}",
  target: "Bonjour {",
});
assertAllIncludes(invalidSourceFallbackMarkup, ["Current source", "$fresh", "unclosed-placeholder"]);

const editableRawMarkup = renderEditor({ initialMode: "raw" });
assertAllIncludes(editableRawMarkup, [
  'aria-label="Raw target MF2 Message"',
  "mf2-raw",
  "mf2-raw-shortcuts",
  "Parser errors are highlighted inline.",
  'role="group"',
  "Restore",
]);
assertAllNotIncludes(editableRawMarkup, ["contentEditable=", "mf2-prose", "mf2-form-row"]);

const controlledRawMarkup = renderEditor({ initialMode: "rich", mode: "raw" });
assertAllIncludes(controlledRawMarkup, ["mf2-raw", "mf2-raw-shortcuts", 'role="group"']);
assertAllNotIncludes(controlledRawMarkup, ["contentEditable=", "mf2-prose", "mf2-form-row"]);

const readOnlyRawMarkup = renderEditor({ initialMode: "raw", readOnly: true });
assertAllIncludes(readOnlyRawMarkup, ["mf2-raw", "missing-source-placeholder"]);
assertAllNotIncludes(readOnlyRawMarkup, ["contentEditable=", "mf2-prose", "mf2-form-row", "mf2-raw-shortcuts", "Parser errors are highlighted inline.", "Restore"]);

const denseMarkup = renderEditor({
  args: { name: "Ada" },
  showPreview: false,
  showSource: false,
  target: "Bonjour {$name}",
});
assertAllIncludes(denseMarkup, ['data-mf2-editor="prosemirror"', "mf2-form-contract"]);
assertAllNotIncludes(denseMarkup, ["mf2-source-prose", "mf2-preview-row", "mf2-args"]);

const previewOnlyMarkup = renderEditor({
  args: { name: "Ada" },
  showArgumentInputs: false,
  target: "Bonjour {$name}",
});
assertAllIncludes(previewOnlyMarkup, ["mf2-preview-row-single", "<output"]);
assertNotIncludes(previewOnlyMarkup, "mf2-args");

const rtlMarkup = renderEditor({
  args: { name: "Ada" },
  locale: "ar",
  target: "Bonjour {$name}",
});
assertAllIncludes(rtlMarkup, ['data-target-direction="rtl"', 'dir="rtl"', '<output dir="rtl"']);

const customLocaleMarkup = renderEditor({
  locale: "pt-BR",
  localeOptions: [
    { label: "French", value: "fr" },
    { label: "Duplicate French", value: "fr" },
    { label: "Arabic", value: "ar" },
  ],
});
assertAllIncludes(customLocaleMarkup, ['<option value="pt-BR" selected="">pt-BR</option>', '<option value="fr">French</option>', '<option value="ar">Arabic</option>']);
assertNotIncludes(customLocaleMarkup, "Duplicate French");

const wildcardSource = `.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare * singular {{Schild}}
* * * {{fallback}}`;
const wildcardTarget = `.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare dative singular {{Bouclier}}
* * * {{Bouclier}}`;
const richComparisonMarkup = renderEditor({
  showActiveSourceComparison: true,
  showSource: false,
  source: wildcardSource,
  target: wildcardTarget,
});
assertAllIncludes(richComparisonMarkup, [
  "mf2-active-source-comparison",
  "usage: bare / case: fallback / number: singular",
  "Schild",
]);
assertNotIncludes(richComparisonMarkup, "mf2-source-prose");

const rawComparisonMarkup = renderEditor({
  initialMode: "raw",
  showActiveSourceComparison: true,
  showSource: false,
  source: wildcardSource,
  target: wildcardTarget,
});
assertAllIncludes(rawComparisonMarkup, [
  "mf2-active-source-comparison",
  "mf2-raw",
  "usage: bare / case: fallback / number: singular",
]);

const noMatchingSourceFormMarkup = renderEditor({
  showActiveSourceComparison: true,
  source: `.input {$kind :string}
.input {$name :string}
.match $kind
a {{A {$name}}}`,
  target: `.input {$kind :string}
.input {$name :string}
.match $kind
b {{B}}`,
});
assertAllIncludes(noMatchingSourceFormMarkup, ["No matching source form", "mf2-shortcuts"]);
assertAllNotIncludes(noMatchingSourceFormMarkup, ["mf2-active-source-comparison", "Restore"]);

const renamedSelectorSourceFormMarkup = renderEditor({
  showActiveSourceComparison: true,
  source: `.input {$count :number}
.match $count
one {{Source one {$count}}}
* {{Source fallback {$count}}}`,
  target: `.input {$total :number}
.match $total
one {{Target one}}
* {{Target fallback}}`,
});
assertAllIncludes(renamedSelectorSourceFormMarkup, ["No matching source form", "new-selector", "missing-source-selector"]);
assertAllNotIncludes(renamedSelectorSourceFormMarkup, ["mf2-active-source-comparison", "Restore"]);

const annotationChangedSourceFormMarkup = renderEditor({
  showActiveSourceComparison: true,
  source: `.input {$count :number}
.match $count
one {{Source one {$count}}}
* {{Source fallback {$count}}}`,
  target: `.input {$count :string}
.match $count
one {{Target one}}
* {{Target fallback}}`,
});
assertAllIncludes(annotationChangedSourceFormMarkup, ["No matching source form", "selector-annotation-mismatch"]);
assertAllNotIncludes(annotationChangedSourceFormMarkup, ["mf2-active-source-comparison", "Restore"]);

const fallbackPlural = `.input {$count :number}
.match $count
* {{Files}}`;
const localeRowHelperMarkup = renderEditor({
  locale: "ar",
  source: fallbackPlural,
  target: fallbackPlural,
});
assertIncludes(localeRowHelperMarkup, 'aria-label="Add 5 target-locale forms: $count: zero, one, two, few, many"');

const invalidLocaleRowsMarkup = renderEditor({
  locale: "en",
  source: fallbackPlural,
  target: `.input {$count :number}
.match $count
zero {{}}
few {{}}
* {{Files}}`,
});
assertIncludes(invalidLocaleRowsMarkup, 'aria-label="Remove 2 blank target-locale forms: $count: zero, few"');

const invalidMultiSelectorRowMarkup = renderEditor({
  locale: "en",
  source: `.input {$count :number}
.input {$total :number}
.match $count $total
* * {{Files}}`,
  target: `.input {$count :number}
.input {$total :number}
.match $count $total
zero zero {{}}
* * {{Files}}`,
});
assertIncludes(invalidMultiSelectorRowMarkup, 'aria-label="Remove 1 blank target-locale form: $count: zero; $total: zero"');

function renderEditor(props: Partial<ComponentProps<typeof Mf2TranslationEditor>> = {}) {
  return renderToStaticMarkup(
    <Mf2TranslationEditor
      locale="fr"
      source="Hello {$name}"
      target="Bonjour"
      {...props}
    />,
  );
}

function assertAllIncludes(value: string, expectedValues: Array<string>) {
  expectedValues.forEach((expected) => assertIncludes(value, expected));
}

function assertAllNotIncludes(value: string, unexpectedValues: Array<string>) {
  unexpectedValues.forEach((unexpected) => assertNotIncludes(value, unexpected));
}

function assertIncludes(value: string, expected: string) {
  if (!value.includes(expected)) {
    throw new Error(`Expected rendered markup to include ${JSON.stringify(expected)}.`);
  }
}

function assertNotIncludes(value: string, unexpected: string) {
  if (value.includes(unexpected)) {
    throw new Error(`Expected rendered markup not to include ${JSON.stringify(unexpected)}.`);
  }
}
