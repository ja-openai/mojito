package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

/**
 * Storage for blobs with optional expiration.
 *
 * @author jaurambault
 */
@Entity
@Table(
    name = "mblob",
    indexes = {
      @Index(name = "UK__MBLOB__NAME", columnList = "name", unique = true),
      @Index(name = "I__MBLOB__EXPIRATION_DATE_ID", columnList = "expiration_date,id")
    })
public class MBlob extends SettableAuditableEntity {

  @Column(name = "name")
  private String name;

  @Column(name = "content", length = Integer.MAX_VALUE)
  @Lob
  private byte[] content;

  @Column(name = "expire_after_seconds")
  private Long expireAfterSeconds;

  @Column(
      name = "expiration_date",
      insertable = false,
      updatable = false,
      columnDefinition =
          "datetime generated always as (timestampadd(SECOND, expire_after_seconds, created_date))")
  private ZonedDateTime expirationDate;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public byte[] getContent() {
    return content;
  }

  public void setContent(byte[] content) {
    this.content = content;
  }

  public long getExpireAfterSeconds() {
    return expireAfterSeconds;
  }

  public boolean hasExpiration() {
    return expireAfterSeconds != null;
  }

  public void setExpireAfterSeconds(long expireAfterSeconds) {
    this.expireAfterSeconds = expireAfterSeconds;
  }

  public void clearExpiration() {
    expireAfterSeconds = null;
  }

  public ZonedDateTime getExpirationDate() {
    return expirationDate;
  }
}
