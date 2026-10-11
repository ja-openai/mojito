// Exercise the real Node resolution graph, including transitive dependencies.
export async function load(url, context, nextLoad) {
  if (/\/mf2\/javascript\/src\//.test(url)) {
    process.stderr.write(`MF2_LOADED ${url}\n`);
    if (
      process.env.MF2_FORBID_PARSER === "1" &&
      /\/(?:parser|index)\.js$/.test(url)
    ) {
      throw new Error(
        `Production entrypoint loaded parser or combined core entrypoint: ${url}`,
      );
    }
  }
  return nextLoad(url, context);
}
