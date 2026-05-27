export type EditorKeyEvent = Pick<KeyboardEvent, "altKey" | "ctrlKey" | "key" | "metaKey" | "shiftKey">;

export type FormNavigationIntent = {
  direction: -1 | 1;
  reason: "command-enter" | "shift-arrow";
};

export function formNavigationIntent(event: EditorKeyEvent): FormNavigationIntent | null {
  const hasCommandModifier = event.metaKey || event.ctrlKey;
  const hasTextNavigationModifier = hasCommandModifier || event.altKey;
  if ((event.key === "ArrowDown" || event.key === "ArrowUp") && event.shiftKey && !hasTextNavigationModifier) {
    return {
      direction: event.key === "ArrowDown" ? 1 : -1,
      reason: "shift-arrow",
    };
  }
  if (event.key === "Enter" && !event.shiftKey && !event.altKey && hasCommandModifier) {
    return {
      direction: 1,
      reason: "command-enter",
    };
  }
  return null;
}

export function formRowActivationIntent(event: EditorKeyEvent) {
  if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return false;
  return event.key === "Enter" || event.key === " ";
}
