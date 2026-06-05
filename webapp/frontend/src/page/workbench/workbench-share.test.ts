// @vitest-environment node

import { describe, expect, it } from 'vitest';

import { buildWorkbenchSharePayload } from './workbench-share';
import type { WorkbenchRow } from './workbench-types';

describe('buildWorkbenchSharePayload', () => {
  it('uses exact search for shared TextUnit ID sets', () => {
    const payload = buildWorkbenchSharePayload({
      mode: 'search-ids',
      searchRequest: {
        repositoryIds: [1],
        localeTags: ['fr-FR'],
        limit: 10,
      },
      rows: [{ tmTextUnitId: 30690 }, { tmTextUnitId: 30691 }] as WorkbenchRow[],
      localeFocus: 'USE_SEARCH',
    });

    expect(payload.searchRequest).toMatchObject({
      searchAttribute: 'tmTextUnitIds',
      searchType: 'exact',
      searchText: '30690,30691',
      limit: 2,
      offset: 0,
    });
  });
});
