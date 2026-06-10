import { describe, expect, it } from 'vitest';

import { md5HexUtf8 } from './md5';

describe('md5HexUtf8', () => {
  it('matches known md5 values', () => {
    expect(md5HexUtf8('')).toBe('d41d8cd98f00b204e9800998ecf8427e');
    expect(md5HexUtf8('abc')).toBe('900150983cd24fb0d6963f7d28e17f72');
    expect(md5HexUtf8('Button labelShown on checkout')).toBe('e7a945a3827fa7345133d30a9e6436dc');
  });

  it('hashes normalized unicode input', () => {
    expect(md5HexUtf8('Cafe\u0301Description'.normalize('NFC'))).toBe(
      '3ceeb074a88dc53cceeca91dec9889ac',
    );
  });
});
