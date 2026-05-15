package com.box.l10n.mojito.localtm.merger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import java.time.ZonedDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BranchStateTextUnit(
    @JsonProperty("tmTextUnitId") Long tmTextUnitId,
    @JsonProperty("assetTextUnitId") Long assetTextUnitId,
    @JsonProperty("md5") String md5,
    @JsonProperty("createdDate") ZonedDateTime createdDate,
    @JsonProperty("name") String name,
    @JsonProperty("source") String source,
    @JsonProperty("comments") String comments,
    @JsonProperty("pluralForm") String pluralForm,
    @JsonProperty("pluralFormOther") String pluralFormOther,
    @JsonProperty("branchNameToBranchDatas")
        ImmutableMap<String, BranchData> branchNameToBranchDatas) {

  public BranchStateTextUnit {
    branchNameToBranchDatas =
        branchNameToBranchDatas == null
            ? ImmutableMap.of()
            : ImmutableMap.copyOf(branchNameToBranchDatas);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Long getTmTextUnitId() {
    return tmTextUnitId;
  }

  public Long getAssetTextUnitId() {
    return assetTextUnitId;
  }

  public String getMd5() {
    return md5;
  }

  public ZonedDateTime getCreatedDate() {
    return createdDate;
  }

  public String getName() {
    return name;
  }

  public String getSource() {
    return source;
  }

  public String getComments() {
    return comments;
  }

  public String getPluralForm() {
    return pluralForm;
  }

  public String getPluralFormOther() {
    return pluralFormOther;
  }

  public ImmutableMap<String, BranchData> getBranchNameToBranchDatas() {
    return branchNameToBranchDatas;
  }

  public BranchStateTextUnit withTmTextUnitId(Long tmTextUnitId) {
    return new BranchStateTextUnit(
        tmTextUnitId,
        assetTextUnitId,
        md5,
        createdDate,
        name,
        source,
        comments,
        pluralForm,
        pluralFormOther,
        branchNameToBranchDatas);
  }

  public BranchStateTextUnit withAssetTextUnitId(Long assetTextUnitId) {
    return new BranchStateTextUnit(
        tmTextUnitId,
        assetTextUnitId,
        md5,
        createdDate,
        name,
        source,
        comments,
        pluralForm,
        pluralFormOther,
        branchNameToBranchDatas);
  }

  public BranchStateTextUnit withCreatedDate(ZonedDateTime createdDate) {
    return new BranchStateTextUnit(
        tmTextUnitId,
        assetTextUnitId,
        md5,
        createdDate,
        name,
        source,
        comments,
        pluralForm,
        pluralFormOther,
        branchNameToBranchDatas);
  }

  public BranchStateTextUnit withBranchNameToBranchDatas(
      ImmutableMap<String, BranchData> branchNameToBranchDatas) {
    return new BranchStateTextUnit(
        tmTextUnitId,
        assetTextUnitId,
        md5,
        createdDate,
        name,
        source,
        comments,
        pluralForm,
        pluralFormOther,
        branchNameToBranchDatas);
  }

  public static class Builder {
    private Long tmTextUnitId;
    private Long assetTextUnitId;
    private String md5;
    private ZonedDateTime createdDate;
    private String name;
    private String source;
    private String comments;
    private String pluralForm;
    private String pluralFormOther;
    private ImmutableMap<String, BranchData> branchNameToBranchDatas = ImmutableMap.of();

    public Builder tmTextUnitId(Long tmTextUnitId) {
      this.tmTextUnitId = tmTextUnitId;
      return this;
    }

    public Builder assetTextUnitId(Long assetTextUnitId) {
      this.assetTextUnitId = assetTextUnitId;
      return this;
    }

    public Builder md5(String md5) {
      this.md5 = md5;
      return this;
    }

    public Builder createdDate(ZonedDateTime createdDate) {
      this.createdDate = createdDate;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    public Builder comments(String comments) {
      this.comments = comments;
      return this;
    }

    public Builder pluralForm(String pluralForm) {
      this.pluralForm = pluralForm;
      return this;
    }

    public Builder pluralFormOther(String pluralFormOther) {
      this.pluralFormOther = pluralFormOther;
      return this;
    }

    public Builder branchNameToBranchDatas(
        ImmutableMap<String, BranchData> branchNameToBranchDatas) {
      this.branchNameToBranchDatas = branchNameToBranchDatas;
      return this;
    }

    public BranchStateTextUnit build() {
      return new BranchStateTextUnit(
          tmTextUnitId,
          assetTextUnitId,
          md5,
          createdDate,
          name,
          source,
          comments,
          pluralForm,
          pluralFormOther,
          branchNameToBranchDatas);
    }
  }
}
