# MF2 Reference Comparison

Reference harnesses compare Mojito's shared MF2 fixtures against upstream or
widely used implementations. They are intentionally separate from the runtime
libraries so experimental dependencies do not leak into production packages.

Current harnesses:

- `icu4j/`: ICU4J MessageFormat 2 technical preview
- `icu4cxx/`: optional ICU4C++ MessageFormat 2 technical preview harness
- `messageformat-js/`: npm `messageformat` v4 parser/runtime benchmark against
  the native JavaScript core
- `fixtures/currency-simple-vs-icu4j.json`: diagnostic fixture showing where a
  dependency-free sample `:currency` formatter matches or diverges from ICU4J
  currency formatting

Reference results are compatibility signals, not a production API commitment.
ICU's MF2 APIs and syntax support are still marked technical preview.

## Selection-Operand/Resolved-Value Gate and ICU4C Extension

Run the count-asserting ICU4J gate. If the ICU4C++ preview headers are
available, the same command also runs that optional extension and labels it as
such:

```sh
sh check.sh
```

Require ICU4C++ availability in a provisioned environment with:

```sh
MF2_REQUIRE_ICU4C=1 sh check.sh
```

The script asserts the exact summaries and the identity/reason of each known
ICU4C unsupported row, so a silently added, skipped, failed, or newly
unsupported row fails the gate. ICU4J currently passes 37 common selection
cases, five ICU4J-specific selection cases, one recovery case, and one common
currency resolved-value case. The optional ICU4C extension passes 36 of the 37
common selection cases, records the combined `:percent` option-chain row as
one expected unsupported case, passes the recovery case, and records the
currency resolved-value row as a second expected unsupported case.

The matrix also includes four percent cases and one numeric-provenance
`:offset` case in the ICU4J-specific set. It spans all six CLDR cardinal
categories, integers, decimals, visible fractions, negatives, zero, grouped
millions, exact numeric keys, exact-over-category preference, canonical
integer serialization where TR35 requires it, option carry/override/filtering,
semantic-value provenance, integer truncation, and percent scaling. Four
additional variable-offset/declaration-chain cases are
adapter-only because the ICU4J technical preview rejects the declaration form
used by the Mojito runtimes.

The Python/Babel, JavaScript/Intl, and PHP/Intl adapter-loader target is 47
fixture cases after adding the explicit currency-replacement row. The JVM,
Swift Foundation, and Rust ICU4X adapter checks gate the key
selection/display-separation invariants in their native test/demo suites.

The resolved-value currency pair separates an intentional Mojito policy from
an objective adapter invariant. For a USD `:currency` local reannotated with
`:number`, Mojito treats `:number` as a currency-type/provenance barrier;
ICU4J 78.3 instead carries USD and renders `Value $42.00`, while ICU4C 77.1
reports `U_MF_UNKNOWN_FUNCTION_ERROR`. The adapter row supplies an explicit
final `currency=EUR`, which must replace any earlier currency and render
`Value €42.00`; ICU4J agrees with that observable result.

TR35 requires canonical integer exact serialization only for integer values
with none of `minimumFractionDigits`, `minimumIntegerDigits`,
`minimumSignificantDigits`, or `maximumSignificantDigits` set. The common
integer row stays inside that subset. The direct decimal offset result `-0.9`
matching key `-0.9` is instead one explicit Mojito cross-runtime policy;
ICU4J 78.3 formats the value but selects the fallback, and no broader decimal
serialization parity is claimed.

The strengthened option row jointly verifies inherited `signDisplay` and
visible fraction digits, number → integer → number option filtering, and
percent → number option retention. ICU4J renders
`fallback +1.0; integer 1.00; percent 1.00`; ICU4C reports
`U_MF_UNKNOWN_FUNCTION_ERROR` for the contained `:percent`. The shared
expectation is `other +1.0; integer 1; percent 1.00` under current TR35 rules.

The ICU4J-specific offset row selects direct `-0.9`, selects category `one`
and canonical display `1` for `0.0 + 1`, and retains the rounded-source check
`1.9 + 1 = 2.9`. ICU4J falls back only for the implementation-defined decimal
exact key; ICU4C 77.1 does not implement `:offset` and reports
`U_MF_UNKNOWN_FUNCTION_ERROR`, so this row is excluded from its extension.

One invalid-numeric-key continuation row lives separately under
`fixtures/selection-recovery/common`. The ICU harnesses prove that formatting
continues to the later valid exact variant, but their string-only APIs do not
expose the required `bad-variant-key` error. The shared conformance fixture
asserts the output and exact error list; keeping these two checks separate
avoids weakening the no-error contract of the adapter matrix.

See `../conformance/coverage-audit.md` for the exact comparison layers, compact
difference-disposition ledger, and verified explanation of the former
grouped-number plural-selection gap.

## Broad Diagnostic Comparison

The ICU harnesses can still be pointed at all shared source fixtures. This is a
diagnostic compatibility report, not a clean gate: preview syntax/features and
locale-data versions differ intentionally. The pre-fix audit on 2026-08-27 at
base revision `50cecdfd93` covered 845 output cases. ICU4J 78.3 reported 835
passed, 5 mismatched, and 5 unsupported; ICU4C 77.1 reported 797 passed, 22
mismatched, and 26 unsupported. These dated counts are evidence for the audit,
not a baseline to preserve after adding confirmed regression cases.

Known observations:

- Both ICU harnesses reject the unannotated string selector fixtures because MF2
  requires selectors to be tied to a declaration with a function.
- Both ICU harnesses localize Russian numeric output, so `1.5` formats as
  `1,5`.
- ICU4J 78.3 normalized the decomposed accent in the Unicode literal fixture;
  ICU4C++ 77.1 preserved it in the same fixture.
