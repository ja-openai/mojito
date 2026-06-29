package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Indexed access to compact metadata bits in a compiled term pack. */
class CompiledTermMetadataIndex {

  private static final int GENDER_SHIFT = 4;
  private static final int GENDER_MASK = 0xF;
  private static final int NUMBER_SHIFT = 8;
  private static final int NUMBER_MASK = 0xF;

  private static final int MASCULINE_GENDER = 1;
  private static final int FEMININE_GENDER = 2;
  private static final int NEUTER_GENDER = 3;
  private static final int AMBIGUOUS_GENDER = 4;
  private static final int COMMON_GENDER = 5;

  private static final int SINGULAR_NUMBER = 1;
  private static final int PLURAL_NUMBER = 2;
  private static final int INVARIANT_NUMBER = 3;

  private final Map<String, TermMetadata> metadataByTermId;

  CompiledTermMetadataIndex(CompiledTermPack pack) {
    Objects.requireNonNull(pack, "pack");
    metadataByTermId = new HashMap<>();
    for (TermRow term : pack.terms()) {
      String termId = pack.strings().get(term.id());
      String text = pack.strings().get(term.text());
      String sense = term.sense() == null ? null : pack.strings().get(term.sense());
      TermMetadata metadata =
          new TermMetadata(
              termId,
              text,
              sense,
              term.featureBits(),
              gender(term.featureBits()),
              number(term.featureBits()));
      if (metadataByTermId.put(termId, metadata) != null) {
        throw new IllegalArgumentException("Duplicate compiled term metadata: " + termId);
      }
    }
  }

  TermMetadata metadata(String termId) {
    TermMetadata metadata = metadataByTermId.get(termId);
    if (metadata == null) {
      throw new IllegalArgumentException("Missing compiled term metadata: " + termId);
    }
    return metadata;
  }

  private static String gender(int featureBits) {
    return switch ((featureBits >> GENDER_SHIFT) & GENDER_MASK) {
      case MASCULINE_GENDER -> "masculine";
      case FEMININE_GENDER -> "feminine";
      case NEUTER_GENDER -> "neuter";
      case AMBIGUOUS_GENDER -> "ambiguous";
      case COMMON_GENDER -> "common";
      default -> null;
    };
  }

  private static String number(int featureBits) {
    return switch ((featureBits >> NUMBER_SHIFT) & NUMBER_MASK) {
      case SINGULAR_NUMBER -> "singular";
      case PLURAL_NUMBER -> "plural";
      case INVARIANT_NUMBER -> "invariant";
      default -> null;
    };
  }

  record TermMetadata(
      String termId, String text, String sense, int featureBits, String gender, String number) {

    public TermMetadata {
      termId = requireText(termId, "termId");
      text = requireText(text, "text");
      if (sense != null && sense.isBlank()) {
        throw new IllegalArgumentException("sense must not be blank");
      }
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
