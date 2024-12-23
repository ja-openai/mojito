package com.box.l10n.mojito.utils;

import com.google.common.base.Splitter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OptionsParser {

  Map<String, String> options = new LinkedHashMap<>();

  public OptionsParser(List<String> options) {
    if (options != null) {
      for (String option : options) {
        List<String> optionKeyAndValue = Splitter.on("=").limit(2).splitToList(option);
        if (optionKeyAndValue.size() == 2) {
          this.options.put(optionKeyAndValue.get(0), optionKeyAndValue.get(1));
        }
      }
    }
  }

  public void getString(String key, Consumer<String> consumer) {
    if (this.options.containsKey(key)) {
      consumer.accept(this.options.get(key));
    }
  }

  public void getBoolean(String key, Consumer<Boolean> consumer) {
    if (this.options.containsKey(key)) {
      consumer.accept(Boolean.valueOf(this.options.get(key)));
    }
  }

  public Boolean getBoolean(String key, Boolean defaultValue) {
    Boolean value = defaultValue;

    if (this.options.containsKey(key)) {
      value = Boolean.valueOf(this.options.get(key));
    }

    return value;
  }

  public String getString(String key, String defaultValue) {
    String value = defaultValue;

    if (this.options.containsKey(key)) {
      value = this.options.get(key);
    }

    return value;
  }

  public void getInteger(String key, Consumer<Integer> consumer) {
    if (this.options.containsKey(key)) {
      consumer.accept(Integer.valueOf(this.options.get(key)));
    }
  }
}
