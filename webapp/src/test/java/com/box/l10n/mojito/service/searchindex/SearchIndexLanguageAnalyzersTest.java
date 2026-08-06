package com.box.l10n.mojito.service.searchindex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class SearchIndexLanguageAnalyzersTest {

  @Test
  public void selectsLanguageAnalyzerFromRegionalLocaleTags() {
    assertThat(SearchIndexLanguageAnalyzers.languageKey("en-US")).isEqualTo("en");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("fr-CA")).isEqualTo("fr");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("pt-BR")).isEqualTo("pt_br");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("pt-PT")).isEqualTo("pt");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("nb-NO")).isEqualTo("no");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("nn-NO")).isEqualTo("no");
  }

  @Test
  public void routesEastAsianAndThaiLanguagesToInstalledAnalyzers() {
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-Hans-CN")).isEqualTo("zh_hans");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-CN")).isEqualTo("zh_hans");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-SG")).isEqualTo("zh_hans");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh")).isEqualTo("zh_hans");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-Hant-TW")).isEqualTo("zh_hant");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-TW")).isEqualTo("zh_hant");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-HK")).isEqualTo("zh_hant");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-MO")).isEqualTo("zh_hant");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-Hans-HK")).isEqualTo("zh_hans");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("zh-Hant-CN")).isEqualTo("zh_hant");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("ja-JP")).isEqualTo("ja");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("ko-KR")).isEqualTo("ko");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("th-TH")).isEqualTo("th");
  }

  @Test
  public void fallsBackForUnsupportedOrMissingLanguageTags() {
    assertThat(SearchIndexLanguageAnalyzers.languageKey("sl-SI")).isEqualTo("default");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("am-ET")).isEqualTo("default");
    assertThat(SearchIndexLanguageAnalyzers.languageKey(" ")).isEqualTo("default");
    assertThat(SearchIndexLanguageAnalyzers.languageKey(null)).isEqualTo("default");
  }

  @Test
  public void normalizesCaseAndLegacyLocaleSeparators() {
    assertThat(SearchIndexLanguageAnalyzers.normalizeLocaleTag(" PT_BR ")).isEqualTo("pt-br");
    assertThat(SearchIndexLanguageAnalyzers.languageKey("PT_BR")).isEqualTo("pt_br");
  }
}
