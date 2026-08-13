# mojito-file-formats

Independent Rust implementation of Mojito's portable localization resource
contract. Android XML, Apple strings/string dictionaries/Xcode String Catalogs,
gettext PO, Java properties, and FormatJS JSON are checked against the same real-file fixture
manifest as the integrated Java implementation.

```sh
cargo test --offline
```

The crate never calls Java or Okapi. `quick-xml` performs structural XML
parsing; document types and external entities are rejected. Locale cardinal
categories reuse the existing `mf2/rust/mojito-mf2` crate instead of bundling a
separate CLDR snapshot. Repository builds resolve that crate through its path;
publishing `mojito-file-formats` requires publishing `mojito-mf2` first.

From the repository root, run every shared fixture, native platform oracle, and
the independent Java/Rust suites together:

```sh
python3 file-formats/conformance/run.py --offline
```
