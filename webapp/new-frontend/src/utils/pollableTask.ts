export function normalizePollableTaskErrorMessage(rawErrorMessage: unknown): string {
  if (rawErrorMessage == null) {
    return '';
  }

  if (typeof rawErrorMessage === 'string') {
    const trimmed = rawErrorMessage.trim();
    if (!trimmed) {
      return '';
    }

    try {
      return normalizePollableTaskErrorMessage(JSON.parse(trimmed));
    } catch {
      return trimmed;
    }
  }

  if (typeof rawErrorMessage === 'object') {
    const candidate = rawErrorMessage as { message?: unknown; error?: unknown };
    const fromMessage = normalizePollableTaskErrorMessage(candidate.message);
    if (fromMessage) {
      return fromMessage;
    }
    const fromError = normalizePollableTaskErrorMessage(candidate.error);
    if (fromError) {
      return fromError;
    }
  }

  return String(rawErrorMessage).trim();
}
