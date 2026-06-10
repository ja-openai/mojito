const SHIFT_AMOUNTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14,
  20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6,
  10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

const TABLE = Array.from({ length: 64 }, (_, index) =>
  Math.floor(Math.abs(Math.sin(index + 1)) * 0x100000000),
);

const textEncoder = new TextEncoder();

export function md5HexUtf8(value: string): string {
  const bytes = Array.from(textEncoder.encode(value));
  const bitLength = BigInt(bytes.length) * 8n;

  bytes.push(0x80);
  while (bytes.length % 64 !== 56) {
    bytes.push(0);
  }
  for (let index = 0; index < 8; index += 1) {
    bytes.push(Number((bitLength >> BigInt(index * 8)) & 0xffn));
  }

  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;

  for (let offset = 0; offset < bytes.length; offset += 64) {
    const words = new Array<number>(16);
    for (let index = 0; index < 16; index += 1) {
      const wordOffset = offset + index * 4;
      words[index] =
        (bytes[wordOffset] ?? 0) |
        ((bytes[wordOffset + 1] ?? 0) << 8) |
        ((bytes[wordOffset + 2] ?? 0) << 16) |
        ((bytes[wordOffset + 3] ?? 0) << 24);
    }

    let a = a0;
    let b = b0;
    let c = c0;
    let d = d0;

    for (let index = 0; index < 64; index += 1) {
      let f: number;
      let g: number;

      if (index < 16) {
        f = (b & c) | (~b & d);
        g = index;
      } else if (index < 32) {
        f = (d & b) | (~d & c);
        g = (5 * index + 1) % 16;
      } else if (index < 48) {
        f = b ^ c ^ d;
        g = (3 * index + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * index) % 16;
      }

      const nextD = d;
      d = c;
      c = b;
      b =
        (b +
          leftRotate(
            (a + f + (TABLE[index] ?? 0) + (words[g] ?? 0)) >>> 0,
            SHIFT_AMOUNTS[index] ?? 0,
          )) >>>
        0;
      a = nextD;
    }

    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }

  return [a0, b0, c0, d0].map(toLittleEndianHex).join('');
}

function leftRotate(value: number, shift: number): number {
  return ((value << shift) | (value >>> (32 - shift))) >>> 0;
}

function toLittleEndianHex(value: number): string {
  let hex = '';
  for (let index = 0; index < 4; index += 1) {
    hex += ((value >>> (index * 8)) & 0xff).toString(16).padStart(2, '0');
  }
  return hex;
}
