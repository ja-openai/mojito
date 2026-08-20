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
      maxHeight: 544,
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
      maxHeight: 692,
      top: 468,
    });
  });

  it('caps the panel to the larger side when it fits neither above nor below', () => {
    vi.stubGlobal('innerHeight', 800);

    expect(
      getAnchoredDropdownPanelStyle({
        rect: new DOMRect(100, 360, 160, 32),
        align: 'left',
        maxWidth: 320,
        panelHeight: 600,
      }),
    ).toMatchObject({
      maxHeight: 384,
      top: 400,
    });
  });
});
