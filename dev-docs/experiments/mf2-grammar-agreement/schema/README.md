# Grammar Agreement Schemas

These schemas define the shared fixture/resource-bundle and diagnostic contract
used by the Python and JavaScript prototypes and intended for Java/Rust
implementations.

- `grammar-bundle.schema.json`: canonical resource bundle and fixture shape.
- `grammar-diagnostic.schema.json`: structured validation diagnostic shape.
- `locale-profile.schema.json`: locale profile shape for term-form
  requirements, metadata requirements, and compact flag layouts.

The schemas are intentionally permissive around term form and morphology fields
because locale profiles own language-specific feature inventories. They are
strict around the parts runtimes must agree on:

- bundle has `schema`, `locale`, and `messages`;
- each message has a `value`;
- examples contain either `output` or `error`;
- diagnostics have a stable `code`.

Locale-specific constraints should be layered on top through profile validation,
not hardcoded into the base schema.
