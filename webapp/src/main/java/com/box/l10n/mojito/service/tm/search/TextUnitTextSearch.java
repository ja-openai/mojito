package com.box.l10n.mojito.service.tm.search;

import java.util.ArrayList;
import java.util.List;

public class TextUnitTextSearch {

  TextUnitTextSearchBooleanOperator operator = TextUnitTextSearchBooleanOperator.AND;
  List<TextUnitTextSearchPredicate> predicates = new ArrayList<>();

  public TextUnitTextSearchBooleanOperator getOperator() {
    return operator;
  }

  public void setOperator(TextUnitTextSearchBooleanOperator operator) {
    this.operator = operator;
  }

  public List<TextUnitTextSearchPredicate> getPredicates() {
    return predicates;
  }

  public void setPredicates(List<TextUnitTextSearchPredicate> predicates) {
    this.predicates = predicates;
  }
}
