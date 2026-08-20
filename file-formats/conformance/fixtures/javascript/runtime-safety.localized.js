globalThis.__portableLocalizationExecuted = false;
export const runtimeSafety = {
  "windows.path": "C:\\temp\\new",
  "trailing.backslash": "ends\\",
  "template.literal": `\${globalThis.__portableLocalizationExecuted = true}`,
  "template.escaped-injection": `\\\${globalThis.__portableLocalizationExecuted = true}`,
  "template.placeholder": `Bonjour ${globalThis.__portableLocalizationName}`,
  "template.escaped-placeholder": `Port français ${globalThis.__portableLookup["C:\\harbor"]}`,
};
