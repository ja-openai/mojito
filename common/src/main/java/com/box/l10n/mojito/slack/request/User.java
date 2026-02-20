package com.box.l10n.mojito.slack.request;

public class User {
  String id;
  String teamId;
  String name;
  String real_name;
  String email;
  Profile profile;

  public static class Profile {
    String email;

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTeamId() {
    return teamId;
  }

  public void setTeamId(String teamId) {
    this.teamId = teamId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getReal_name() {
    return real_name;
  }

  public void setReal_name(String real_name) {
    this.real_name = real_name;
  }

  public String getEmail() {
    if (email != null && !email.isBlank()) {
      return email;
    }
    return profile != null ? profile.getEmail() : null;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public Profile getProfile() {
    return profile;
  }

  public void setProfile(Profile profile) {
    this.profile = profile;
  }
}
