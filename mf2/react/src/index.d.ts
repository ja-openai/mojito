import type { ReactNode } from "react";
import type { MF2BidiIsolation, MF2Part } from "@mojito-mf2/core/formatter";
import type { MF2Message } from "@mojito-mf2/core";

export type MF2Resources = Record<
  string,
  Record<string, Record<string, MF2Message>>
>;
export type MF2Arguments = Record<
  string,
  string | number | boolean | bigint | null | undefined | Date
>;
export type MF2MarkupRenderers = Record<
  string,
  (children: ReactNode) => ReactNode
>;
export type MF2Config = {
  resources: MF2Resources;
  locale: string;
  sourceLocale?: string;
  timeZone?: string;
  bidiIsolation?: MF2BidiIsolation;
  onError?: (
    error: Error,
    context: { resource: string; name: string; locale: string },
  ) => void;
};
export type MF2Runtime = {
  readonly locale: string;
  format(resource: string, name: string, args?: MF2Arguments): string;
  render(
    resource: string,
    name: string,
    args?: MF2Arguments,
    markupRenderers?: MF2MarkupRenderers,
  ): ReactNode;
};
export function createMF2(config: MF2Config): MF2Runtime;
export function MF2Provider(
  props: MF2Config & { children?: ReactNode },
): ReactNode;
export function useMF2(): MF2Runtime;
export function MF2(props: {
  resource: string;
  name: string;
  args?: MF2Arguments;
  markupRenderers?: MF2MarkupRenderers;
}): ReactNode;
export function renderMF2Parts(
  parts: MF2Part[],
  markupRenderers?: MF2MarkupRenderers,
  options?: { bidiIsolation?: MF2BidiIsolation },
): ReactNode;
