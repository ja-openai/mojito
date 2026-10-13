import type { ReactNode } from "react";
import type { MF2Arguments, MF2MarkupRenderers, MF2Runtime } from "./index.js";

export type MessageSchema = Record<
  string,
  { args: MF2Arguments; markupRenderers: MF2MarkupRenderers }
>;
type SingleKey<K, All = K> = K extends unknown
  ? [All] extends [K]
    ? K
    : never
  : never;
type Exact<Actual, Expected> = Actual &
  Record<Exclude<keyof Actual, keyof Expected>, never>;
type Arguments<S extends MessageSchema, K extends keyof S> = S[K]["args"];
type MarkupRenderers<
  S extends MessageSchema,
  K extends keyof S,
> = S[K]["markupRenderers"];
type ArgsProp<S extends MessageSchema, K extends keyof S, A> = keyof Arguments<
  S,
  K
> extends never
  ? { args?: Exact<A, Arguments<S, K>> }
  : { args: Exact<A, Arguments<S, K>> };
type MarkupRenderersProp<
  S extends MessageSchema,
  K extends keyof S,
  C,
> = keyof MarkupRenderers<S, K> extends never
  ? { markupRenderers?: Exact<C, MarkupRenderers<S, K>> }
  : { markupRenderers: Exact<C, MarkupRenderers<S, K>> };

export type TypedRuntime<S extends MessageSchema> = {
  format<
    K extends keyof S,
    A extends Arguments<S, NoInfer<K>> = Arguments<S, K>,
  >(
    name: K & SingleKey<K>,
    ...args: keyof Arguments<S, K> extends never
      ? [args?: Exact<A, Arguments<S, K>>]
      : [args: Exact<A, Arguments<S, K>>]
  ): string;
  render<
    K extends keyof S,
    A extends Arguments<S, NoInfer<K>> = Arguments<S, K>,
    C extends MarkupRenderers<S, NoInfer<K>> = MarkupRenderers<S, K>,
  >(
    name: K & SingleKey<K>,
    ...args: keyof MarkupRenderers<S, K> extends never
      ? keyof Arguments<S, K> extends never
        ? [
            args?: Exact<A, Arguments<S, K>>,
            markupRenderers?: Exact<C, MarkupRenderers<S, K>>,
          ]
        : [
            args: Exact<A, Arguments<S, K>>,
            markupRenderers?: Exact<C, MarkupRenderers<S, K>>,
          ]
      : [
          args: Exact<A, Arguments<S, K>>,
          markupRenderers: Exact<C, MarkupRenderers<S, K>>,
        ]
  ): ReactNode;
};

export function createMessages<S extends MessageSchema>(
  resource: string,
): {
  MF2<
    K extends keyof S,
    A extends Arguments<S, NoInfer<K>> = Arguments<S, K>,
    C extends MarkupRenderers<S, NoInfer<K>> = MarkupRenderers<S, K>,
  >(
    props: { name: K & SingleKey<K> } & ArgsProp<S, NoInfer<K>, A> &
      MarkupRenderersProp<S, NoInfer<K>, C>,
  ): ReactNode;
  useMF2(): TypedRuntime<S>;
  bind(formatter: MF2Runtime): TypedRuntime<S>;
};
