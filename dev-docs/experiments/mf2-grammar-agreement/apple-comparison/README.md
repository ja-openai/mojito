# Apple Foundation Comparison

This folder records local probes against Apple Foundation morphology and
inflection APIs. It is intentionally separate from `fixtures/**` because these
results are platform observations, not portable conformance requirements.

Run:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/apple_comparison_runner.py --iterations 5000
```

The runner:

1. runs [apple_morphology_probe.swift](../apple_morphology_probe.swift);
2. records Apple `InflectionRule` outputs and `canInflect(language:)` support;
3. renders equivalent explicit term-level MF2 forms through the local prototype;
4. checks the observed Apple outputs and our expected outputs;
5. reports a simple hot-loop timing comparison.

Current local observation:

- Apple reports `canInflect=true` for `fr` and `de`, false for `ru`, `ar`,
  `ja`, and `cy` on this machine.
- Apple explicit German accusative rendering changes `Schild` to `den Schild`.
- Apple leaves the tested French article/elision constructions unchanged.
- Our explicit term forms render the intended French and German outputs because
  the surface forms are stored directly in `forms.default`.

The Apple API contract is public, but the implementation strategy and lexical
data are not. Treat this probe as compatibility research, not as proof of how
Apple built the engine.
