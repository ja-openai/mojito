import { vi } from 'vitest';

function restoreDescriptor(target: object, name: string, descriptor?: PropertyDescriptor) {
  if (descriptor) {
    Object.defineProperty(target, name, descriptor);
  } else {
    delete (target as Record<string, unknown>)[name];
  }
}

/** Supply layout APIs omitted by jsdom for caret menus and editor scrolling. */
export function installProseMirrorDomMock() {
  const getBoundingClientRect = Object.getOwnPropertyDescriptor(
    Range.prototype,
    'getBoundingClientRect',
  );
  const getClientRects = Object.getOwnPropertyDescriptor(Range.prototype, 'getClientRects');
  const scrollBy = Object.getOwnPropertyDescriptor(window, 'scrollBy');
  const rect = new DOMRect(0, 0, 1, 1);
  const rects = {
    0: rect,
    length: 1,
    item: (index: number) => (index === 0 ? rect : null),
    [Symbol.iterator]: function* () {
      yield rect;
    },
  } as DOMRectList;

  Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => rect,
  });
  Object.defineProperty(Range.prototype, 'getClientRects', {
    configurable: true,
    value: () => rects,
  });
  Object.defineProperty(window, 'scrollBy', { configurable: true, value: vi.fn() });

  return () => {
    restoreDescriptor(Range.prototype, 'getBoundingClientRect', getBoundingClientRect);
    restoreDescriptor(Range.prototype, 'getClientRects', getClientRects);
    restoreDescriptor(window, 'scrollBy', scrollBy);
  };
}
