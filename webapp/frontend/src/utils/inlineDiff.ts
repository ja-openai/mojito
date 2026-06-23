export type InlineDiffPart = {
  kind: 'same' | 'removed' | 'added';
  value: string;
};

type InlineDiffTokenMatch = {
  currentIndex: number;
  proposedIndex: number;
};

type InlineDiffSideParts = {
  old: InlineDiffPart[];
  new: InlineDiffPart[];
};

const DIFF_TOKEN_MATRIX_CELL_LIMIT = 40_000;
const DIFF_TOKEN_PATTERN = /(\s+|[\p{L}\p{N}_]+|[^\s\p{L}\p{N}_]+)/gu;

export function buildInlineDiffParts(
  current: string,
  proposed: string,
  mode: 'old' | 'new',
): InlineDiffPart[] {
  const value = mode === 'old' ? current : proposed;
  if (!current && !proposed) {
    return [];
  }
  if (current === proposed) {
    return [{ kind: 'same', value }];
  }

  const tokenParts = buildTokenDiffParts(current, proposed);
  if (tokenParts) {
    return tokenParts[mode];
  }

  return buildBoundaryDiffParts(current, proposed, mode);
}

function buildTokenDiffParts(current: string, proposed: string): InlineDiffSideParts | null {
  const currentTokens = tokenizeDiffText(current);
  const proposedTokens = tokenizeDiffText(proposed);
  if ((currentTokens.length + 1) * (proposedTokens.length + 1) > DIFF_TOKEN_MATRIX_CELL_LIMIT) {
    return null;
  }

  const matches = getTokenLcsMatches(currentTokens, proposedTokens);
  const oldParts: InlineDiffPart[] = [];
  const newParts: InlineDiffPart[] = [];
  let currentIndex = 0;
  let proposedIndex = 0;

  matches.forEach((match) => {
    appendDiffTokens(oldParts, 'removed', currentTokens, currentIndex, match.currentIndex);
    appendDiffTokens(newParts, 'added', proposedTokens, proposedIndex, match.proposedIndex);
    appendDiffTokens(oldParts, 'same', currentTokens, match.currentIndex, match.currentIndex + 1);
    appendDiffTokens(
      newParts,
      'same',
      proposedTokens,
      match.proposedIndex,
      match.proposedIndex + 1,
    );
    currentIndex = match.currentIndex + 1;
    proposedIndex = match.proposedIndex + 1;
  });

  appendDiffTokens(oldParts, 'removed', currentTokens, currentIndex, currentTokens.length);
  appendDiffTokens(newParts, 'added', proposedTokens, proposedIndex, proposedTokens.length);

  return {
    old: oldParts,
    new: newParts,
  };
}

function tokenizeDiffText(value: string): string[] {
  return value.match(DIFF_TOKEN_PATTERN) ?? [];
}

function getTokenLcsMatches(
  currentTokens: string[],
  proposedTokens: string[],
): InlineDiffTokenMatch[] {
  const currentLength = currentTokens.length;
  const proposedLength = proposedTokens.length;
  const table = Array.from(
    { length: currentLength + 1 },
    () => new Uint16Array(proposedLength + 1),
  );

  for (let currentIndex = currentLength - 1; currentIndex >= 0; currentIndex -= 1) {
    for (let proposedIndex = proposedLength - 1; proposedIndex >= 0; proposedIndex -= 1) {
      if (currentTokens[currentIndex] === proposedTokens[proposedIndex]) {
        table[currentIndex][proposedIndex] = table[currentIndex + 1][proposedIndex + 1] + 1;
      } else {
        table[currentIndex][proposedIndex] = Math.max(
          table[currentIndex + 1][proposedIndex],
          table[currentIndex][proposedIndex + 1],
        );
      }
    }
  }

  const matches: InlineDiffTokenMatch[] = [];
  let currentIndex = 0;
  let proposedIndex = 0;
  while (currentIndex < currentLength && proposedIndex < proposedLength) {
    if (currentTokens[currentIndex] === proposedTokens[proposedIndex]) {
      matches.push({ currentIndex, proposedIndex });
      currentIndex += 1;
      proposedIndex += 1;
    } else if (table[currentIndex + 1][proposedIndex] >= table[currentIndex][proposedIndex + 1]) {
      currentIndex += 1;
    } else {
      proposedIndex += 1;
    }
  }

  return matches;
}

function appendDiffTokens(
  parts: InlineDiffPart[],
  kind: InlineDiffPart['kind'],
  tokens: string[],
  start: number,
  end: number,
) {
  if (start >= end) {
    return;
  }
  appendDiffPart(parts, kind, tokens.slice(start, end).join(''));
}

function appendDiffPart(parts: InlineDiffPart[], kind: InlineDiffPart['kind'], value: string) {
  if (!value) {
    return;
  }
  const previousPart = parts[parts.length - 1];
  if (previousPart?.kind === kind) {
    previousPart.value += value;
    return;
  }
  parts.push({ kind, value });
}

function buildBoundaryDiffParts(
  current: string,
  proposed: string,
  mode: 'old' | 'new',
): InlineDiffPart[] {
  const value = mode === 'old' ? current : proposed;

  let prefixLength = 0;
  while (
    prefixLength < current.length &&
    prefixLength < proposed.length &&
    current[prefixLength] === proposed[prefixLength]
  ) {
    prefixLength += 1;
  }

  let suffixLength = 0;
  while (
    suffixLength < current.length - prefixLength &&
    suffixLength < proposed.length - prefixLength &&
    current[current.length - 1 - suffixLength] === proposed[proposed.length - 1 - suffixLength]
  ) {
    suffixLength += 1;
  }

  const changedStart = prefixLength;
  const changedEnd = value.length - suffixLength;
  const parts: InlineDiffPart[] = [];
  if (changedStart > 0) {
    appendDiffPart(parts, 'same', value.slice(0, changedStart));
  }
  const changedValue = value.slice(changedStart, changedEnd);
  if (changedValue) {
    appendDiffPart(parts, mode === 'old' ? 'removed' : 'added', changedValue);
  }
  if (suffixLength > 0) {
    appendDiffPart(parts, 'same', value.slice(value.length - suffixLength));
  }
  return parts;
}
