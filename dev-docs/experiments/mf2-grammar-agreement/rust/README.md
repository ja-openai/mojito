# Rust Runtime And Compact Pack Plan

Rust is the right target for the compact resource-pack compiler and a small
runtime that can also be exposed to JavaScript through WASM if needed.

This repository is not a Cargo workspace today, so this note specifies the crate
shape instead of adding a docs-only crate.

## Proposed Crates

```text
mf2_grammar_core
  ResourceStore traits
  TermValue / PersonValue / Morphology / TermFormPattern
  GrammarFormatter
  CompiledTermMatcher
  LocaleGrammarAdapter
  Diagnostics

mf2_grammar_profiles
  fr-v1
  de-v1
  ru-v1
  ar-v1
  sw-v1
  ja-v1
  ko-v1
  cy-v1

mf2_grammar_pack
  canonical JSON -> compact JSON
  term MF2 patterns -> compiled sparse matcher tables
  canonical JSON -> binary .gagp
  binary .gagp reader

mf2_grammar_fixtures
  shared fixture runner
```

## Traits

```rust
pub trait ResourceStore {
    fn get_str(&self, key: &str) -> Option<&str>;
    fn get_json(&self, key: &str) -> Option<serde_json::Value>;
    fn get_bytes(&self, key: &str) -> Option<&[u8]>;
}

pub trait GrammarResourceResolver {
    fn message(&self, id: &str, locale: &str) -> Result<MessageValue>;
    fn term(&self, id: &str, locale: &str) -> Result<TermValue>;
    fn person(&self, id: &str, locale: &str) -> Result<PersonValue>;
    fn profile(&self, locale: &str) -> Result<LocaleGrammarProfile>;
}

pub trait LocaleGrammarAdapter {
    fn format_term(&self, value: &TermValue, options: &TermOptions) -> Result<String>;
    fn format_article(&self, value: &TermValue, options: &ArticleOptions) -> Result<String>;
    fn agree(&self, value: &TermValue, target: &MorphTarget, options: &AgreeOptions) -> Result<String>;
    fn required_features(&self, function_name: &str, options: &Options) -> Vec<RequiredFeature>;
}

pub trait TermFormCompiler {
    fn compile(&self, pattern: &str, profile: &LocaleGrammarProfile) -> Result<CompiledTermMatcher>;
}
```

## Binary Pack: `.gagp`

The binary pack is generated output. Canonical JSON remains the TMS/source
format.

Header:

```text
magic           4 bytes   GAGP
version         u8
endian          u8        1 = little
profile_count   u16
message_count   u32
term_count      u32
person_count    u32
string_count    u32
form_count      u32
string_bytes    u32
```

Sections:

```text
profile table
message table
term table
person table
form table
string offset table
utf-8 string blob
optional key hash/fingerprint table
```

The form table stores compiled sparse matchers, not French-specific plural
strings. Source MF2 remains in canonical JSON; production packs should compile
selectors, keys, and output string offsets.

String table:

```text
offset: u32
length: u32
```

Message record:

```text
key_hash: u64
pattern_string_index: u32
profile_index: u16
flags: u16
```

French `fr-v1` term record:

```text
key_hash: u64
text_string_index: u32
flags16: u16
matcher_index: u32  // u32::MAX means fallback metadata path only
```

Generic rich term record:

```text
key_hash: u64
text_string_index: u32
profile_index: u16
flags_width: u8     // 16, 32, or 64
flags_offset: u32
matcher_index: u32
json_extension_index: u32
```

The pack can choose a profile-specific compact record for hot/common languages
and fall back to generic records for complex terms.

## `fr-v1` Flags

```text
bits 0..1   gender: 0 unknown, 1 masculine, 2 feminine, 3 reserved
bits 2..3   number: 0 unknown, 1 singular, 2 plural, 3 invariant
bit  4      reserved for fallback elision metadata
bit  5      hAspired
bit  6      countable
bit  7      hasExplicitForms
bits 8..15  reserved
```

This gives a tiny fallback French noun path:

```text
text string index + 16-bit flags
```

The preferred explicit path adds `matcher_index`, which points at a compiled
term matcher for usage/number/count rows such as bare, definite, indefinite,
and count.

## Lookup

Initial implementation:

```text
sorted key_hash table + binary search + optional fingerprint
```

Future implementation:

```text
minimal perfect hash + fingerprint
```

The binary pack should not require storing full keys at runtime if the calling
application uses generated term/message indexes. For dynamic lookup by string
ID, keep a fingerprint or debug key table.

## Conformance

The Rust fixture runner should load the same canonical JSON fixtures:

```bash
cargo test grammar_fixtures
```

and the binary-pack tests should assert:

```text
canonical JSON fixture
-> binary pack
-> binary store resolver
-> same fixture outputs and diagnostics
```

## Integration Options

- Native Rust runtime for apps that can embed Rust.
- WASM module for web/editor compact-pack preview.
- Java and JS can share the binary pack format without sharing Rust runtime.
- Mojito backend can call Rust pack compiler as a build/export tool later, but
  the first backend implementation should stay Java/Jackson for simplicity.
