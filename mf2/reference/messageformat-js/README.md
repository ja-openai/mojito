# messageformat.js Reference

Throwaway-compatible harness for comparing the native JavaScript MF2 core with
the npm `messageformat` package. This is reference/perf code only; it is not part
of the runtime package. The local core is consumed through `@mojito-mf2/core`
package exports, matching the React and editor wrappers.

Install once:

```sh
npm install
```

Run:

```sh
npm run bench
```

The benchmark uses preconstructed formatter instances for both libraries. It
does not include package install time, process startup, or catalog loading.
