package com.box.l10n.mojito.okapi;

import com.box.l10n.mojito.okapi.filters.AndroidFilter;
import com.box.l10n.mojito.okapi.filters.FilterOptions;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.android-filter")
public class AndroidFilterConfigurationProperties {

  private boolean autoDetectAnchorTags;
  private boolean validateGeneratedResources;

  public boolean isAutoDetectAnchorTags() {
    return autoDetectAnchorTags;
  }

  public void setAutoDetectAnchorTags(boolean autoDetectAnchorTags) {
    this.autoDetectAnchorTags = autoDetectAnchorTags;
  }

  public boolean isValidateGeneratedResources() {
    return validateGeneratedResources;
  }

  public void setValidateGeneratedResources(boolean validateGeneratedResources) {
    this.validateGeneratedResources = validateGeneratedResources;
  }

  public List<String> applyServerDefaults(List<String> requestedFilterOptions) {
    boolean addAutoDetect =
        autoDetectAnchorTags && !hasRequestedAnchorTagOption(requestedFilterOptions);
    if (!addAutoDetect && !validateGeneratedResources) {
      return requestedFilterOptions;
    }

    List<String> effectiveFilterOptions =
        requestedFilterOptions == null
            ? new ArrayList<>()
            : new ArrayList<>(requestedFilterOptions);
    if (addAutoDetect) {
      effectiveFilterOptions.add(AndroidFilter.OPTION_UNESCAPE_ANCHOR_TAGS + "=auto");
    }
    if (validateGeneratedResources) {
      effectiveFilterOptions.removeIf(
          option -> option.startsWith(AndroidFilter.OPTION_VALIDATE_GENERATED_RESOURCES + "="));
      effectiveFilterOptions.add(AndroidFilter.OPTION_VALIDATE_GENERATED_RESOURCES + "=true");
    }
    return effectiveFilterOptions;
  }

  private boolean hasRequestedAnchorTagOption(List<String> requestedFilterOptions) {
    return new FilterOptions(requestedFilterOptions)
            .getString(AndroidFilter.OPTION_UNESCAPE_ANCHOR_TAGS, (String) null)
        != null;
  }
}
