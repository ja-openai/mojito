package com.box.l10n.mojito.service.tm.search;

public class TextUnitTextSearchPredicate {

  TextUnitTextSearchField field;
  SearchType searchType = SearchType.EXACT;
  String value;

  public TextUnitTextSearchField getField() {
    return field;
  }

  public void setField(TextUnitTextSearchField field) {
    this.field = field;
  }

  public SearchType getSearchType() {
    return searchType;
  }

  public void setSearchType(SearchType searchType) {
    this.searchType = searchType;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }
}
