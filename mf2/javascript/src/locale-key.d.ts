export function canonicalLocaleKey(locale: unknown): string;

export function localeLookupChain(locale: unknown): string[];

export function pluralLookupChain(locale: unknown, parents?: Record<string, string>): string[];

export function featureLookupChain(locale: unknown, parents?: Record<string, string>): string[];
