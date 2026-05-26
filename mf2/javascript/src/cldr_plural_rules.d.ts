import type { MF2PluralCategory } from "./types.d.ts";

export class NumberOperands {
  n: number;
  i: number;
  v: number;
  w: number;
  f: number;
  t: number;
  e: number;
  c: number;

  constructor(value: string | number);

  operand(name: string): number;
}

export function selectCardinal(locale: string, value: string | number | NumberOperands): MF2PluralCategory;

export function selectOrdinal(locale: string, value: string | number | NumberOperands): MF2PluralCategory;
