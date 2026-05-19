# MF2 Reference Comparison

Reference harnesses compare Mojito's shared MF2 fixtures against upstream or
widely used implementations. They are intentionally separate from the runtime
libraries so experimental dependencies do not leak into production packages.

Current harnesses:

- `icu4j/`: ICU4J MessageFormat 2 technical preview
- `icu4cxx/`: optional ICU4C++ MessageFormat 2 technical preview harness

Reference results are compatibility signals, not a production API commitment.
ICU's MF2 APIs and syntax support are still marked technical preview.

## Current Local Smoke Results

Run on 2026-05-19 against 35 shared format cases:

- ICU4J 78.3: 29 passed, 2 mismatched, 4 unsupported
- ICU4C++ 77.1: 30 passed, 1 mismatched, 4 unsupported

Known observations:

- Both ICU harnesses reject the unannotated string selector fixtures because MF2
  requires selectors to be tied to a declaration with a function.
- Both ICU harnesses localize Russian numeric output, so `1.5` formats as
  `1,5`.
- ICU4J 78.3 normalized the decomposed accent in the Unicode literal fixture;
  ICU4C++ 77.1 preserved it in the same fixture.
