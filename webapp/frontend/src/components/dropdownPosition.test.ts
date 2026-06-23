import { afterEach, describe, expect, it, vi } from 'vitest';

import { getAnchoredDropdownPanelStyle } from './dropdownPosition';

describe('getAnchoredDropdownPanelStyle', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('opens below the trigger when the panel fits', () => {
    vi.stubGlobal('innerHeight', 800);

    expect(
      getAnchoredDropdownPanelStyle({
        rect: new DOMRect(100, 200, 160, 32),
        align: 'left',
        maxWidth: 320,
        panelHeight: 240,
      }),
    ).toMatchObject({
      top: 240,
    });
  });

  it('opens above the trigger when the panel would be clipped below', () => {
    vi.stubGlobal('innerHeight', 800);

    expect(
      getAnchoredDropdownPanelStyle({
        rect: new DOMRect(100, 716, 160, 32),
        align: 'left',
        maxWidth: 320,
        panelHeight: 240,
      }),
    ).toMatchObject({
      top: 468,
    });
  });
});
