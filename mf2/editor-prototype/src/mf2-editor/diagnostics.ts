import type { EditorDiagnostic } from "./model";

const GLOBAL_INLINE_DIAGNOSTIC_CODES = new Set([
  "missing-source-markup",
  "missing-source-markup-attribute",
  "missing-source-markup-option",
  "missing-source-placeholder",
  "missing-source-selector",
  "missing-source-variant",
  "new-markup",
  "new-markup-attribute",
  "new-markup-option",
  "new-placeholder",
  "new-selector",
  "selector-annotation-mismatch",
  "selector-order-mismatch",
]);

const ROW_HELPER_DIAGNOSTIC_CODES = new Set([
  "missing-locale-plural-variant",
]);

export function diagnosticsNearActiveEditor(
  diagnostics: Array<EditorDiagnostic>,
  activeFormLabel: string,
  mode: "raw" | "rich",
) {
  if (mode === "raw" || activeFormLabel === "Message") {
    return diagnostics.filter(diagnosticBelongsInInlineStrip).slice(0, 3);
  }
  const activeDiagnostics = diagnostics.filter((diagnostic) => diagnosticMatchesForm(diagnostic, activeFormLabel));
  const globalDiagnostics = diagnostics.filter(diagnosticAppliesToWholeMessage);
  return uniqueDiagnostics([...activeDiagnostics, ...globalDiagnostics]).slice(0, 3);
}

export function diagnosticsForForm(diagnostics: Array<EditorDiagnostic>, formLabelText: string) {
  if (formLabelText === "Message") return diagnostics;
  return diagnostics.filter((diagnostic) => diagnosticMatchesForm(diagnostic, formLabelText));
}

export function diagnosticRenderKey(diagnostic: EditorDiagnostic, index: number) {
  return `${diagnosticIdentity(diagnostic)}\u0000${index}`;
}

export function issueSeverity(diagnostics: Array<EditorDiagnostic>) {
  if (diagnostics.some((diagnostic) => diagnostic.severity === "error")) return "error";
  return diagnostics.length ? "warning" : null;
}

function diagnosticMatchesForm(diagnostic: EditorDiagnostic, formLabelText: string) {
  if (diagnostic.formLabel) return diagnostic.formLabel === formLabelText;
  if (diagnostic.formLabels?.length) return diagnostic.formLabels.includes(formLabelText);
  return false;
}

function diagnosticAppliesToWholeMessage(diagnostic: EditorDiagnostic) {
  if (GLOBAL_INLINE_DIAGNOSTIC_CODES.has(diagnostic.code)) return true;
  if (ROW_HELPER_DIAGNOSTIC_CODES.has(diagnostic.code)) return false;
  return !diagnostic.formLabel && !diagnostic.formLabels?.length;
}

function diagnosticBelongsInInlineStrip(diagnostic: EditorDiagnostic) {
  return !ROW_HELPER_DIAGNOSTIC_CODES.has(diagnostic.code);
}

function uniqueDiagnostics(diagnostics: Array<EditorDiagnostic>) {
  const seen = new Set<string>();
  return diagnostics.filter((diagnostic) => {
    const key = diagnosticIdentity(diagnostic);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function diagnosticIdentity(diagnostic: EditorDiagnostic) {
  return [
    diagnostic.severity,
    diagnostic.code,
    diagnostic.message,
    diagnostic.start ?? "",
    diagnostic.end ?? "",
    diagnostic.formLabel ?? "",
    diagnostic.formLabels?.join("\u0001") ?? "",
  ].join("\u0000");
}
