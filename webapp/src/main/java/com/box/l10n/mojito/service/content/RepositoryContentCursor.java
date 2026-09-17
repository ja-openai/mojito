package com.box.l10n.mojito.service.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A bounded seek position scoped to its query, never an authorization token or a snapshot. */
record RepositoryContentCursor(String path, long assetId) {
  static String scope(Object... fields) {
    StringBuilder value = new StringBuilder();
    for (Object field : fields) {
      String part = String.valueOf(field);
      value.append(part.length()).append(':').append(part);
    }
    return DigestUtils.sha256Hex(value.toString());
  }

  String encode(String scope) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeByte(1);
        output.writeUTF(scope);
        output.writeUTF(path);
        output.writeLong(assetId);
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static RepositoryContentCursor decode(String cursor, String scope, boolean directory) {
    if (cursor == null) return null;
    try {
      if (cursor.isEmpty() || cursor.length() > 2048) throw new IOException();
      try (var input =
          new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(cursor)))) {
        if (input.readUnsignedByte() != 1 || !input.readUTF().equals(scope))
          throw new IOException();
        String path = input.readUTF();
        long assetId = input.readLong();
        if (path.isEmpty()
            || path.length() > 255
            || input.available() != 0
            || (directory ? assetId != 0 || !path.endsWith("/") : assetId <= 0)) {
          throw new IOException();
        }
        return new RepositoryContentCursor(path, assetId);
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid content cursor or cursor belongs to different filters; start from the first page");
    }
  }
}
