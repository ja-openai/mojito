package com.box.l10n.mojito.entity.glossary.termindex;

public final class TermIndexReview {

  public static final String STATUS_TO_REVIEW = "TO_REVIEW";
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_REJECTED = "REJECTED";

  public static final String STATUS_FILTER_ALL = "ALL";
  public static final String STATUS_FILTER_NON_REJECTED = "NON_REJECTED";

  public static final String AUTHORITY_DEFAULT = "DEFAULT";
  public static final String AUTHORITY_AI = "AI";
  public static final String AUTHORITY_HUMAN = "HUMAN";

  public static final String REASON_STOP_WORD = "STOP_WORD";
  public static final String REASON_TOO_GENERIC = "TOO_GENERIC";
  public static final String REASON_FALSE_POSITIVE = "FALSE_POSITIVE";
  public static final String REASON_OUT_OF_SCOPE = "OUT_OF_SCOPE";
  public static final String REASON_DUPLICATE = "DUPLICATE";
  public static final String REASON_OTHER = "OTHER";

  private TermIndexReview() {}
}
