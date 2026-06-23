import { describe, expect, it } from 'vitest';

import { buildInlineDiffParts } from './inlineDiff';

describe('buildInlineDiffParts', () => {
  it('keeps shared middle text out of the changed diff span', () => {
    const current =
      'Activé par défaut car Thinking n’est pas activé. Auto enverra vers Thinking Mini quand un raisonnement est demandé.';
    const proposed =
      'Activé par défaut car putsomethingweirdfortesting n’est pas activé. Auto enverra vers Thinking Mini quand un raisonnement est demandé. update';

    expect(buildInlineDiffParts(current, proposed, 'old')).toEqual([
      { kind: 'same', value: 'Activé par défaut car ' },
      { kind: 'removed', value: 'Thinking' },
      {
        kind: 'same',
        value:
          ' n’est pas activé. Auto enverra vers Thinking Mini quand un raisonnement est demandé.',
      },
    ]);
    expect(buildInlineDiffParts(current, proposed, 'new')).toEqual([
      { kind: 'same', value: 'Activé par défaut car ' },
      { kind: 'added', value: 'putsomethingweirdfortesting' },
      {
        kind: 'same',
        value:
          ' n’est pas activé. Auto enverra vers Thinking Mini quand un raisonnement est demandé.',
      },
      { kind: 'added', value: ' update' },
    ]);
  });
});
