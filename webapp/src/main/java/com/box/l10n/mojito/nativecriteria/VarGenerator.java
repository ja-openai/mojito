package com.box.l10n.mojito.nativecriteria;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author jeanaurambault
 */
public class VarGenerator {

  private static final int ALIAS_TRUNCATE_LENGTH = 10;

  static AtomicInteger idGenerator = new AtomicInteger();

  static int nextValue() {
    int incrementAndGet = idGenerator.incrementAndGet();
    if (incrementAndGet == 1000) {
      idGenerator.set(0);
    }
    return incrementAndGet;
  }

  public static String gen(String description) {
    return generateAlias(description.replace("(", "").replace(")", ""), nextValue());
  }

  private static String generateAlias(String description, int unique) {
    return generateAliasRoot(description) + unique + '_';
  }

  private static String generateAliasRoot(String description) {
    String result =
        truncate(unqualifyEntityName(description), ALIAS_TRUNCATE_LENGTH)
            .toLowerCase(Locale.ROOT)
            .replace('/', '_')
            .replace('$', '_');

    result = cleanAlias(result);
    if (result.isEmpty()) {
      return "param";
    }

    return Character.isDigit(result.charAt(result.length() - 1)) ? result + "x" : result;
  }

  private static String truncate(String string, int length) {
    return string.length() <= length ? string : string.substring(0, length);
  }

  private static String unqualifyEntityName(String entityName) {
    String result = unqualify(entityName);
    int slashPos = result.indexOf('/');
    if (slashPos > 0) {
      result = result.substring(0, slashPos - 1);
    }
    return result;
  }

  private static String unqualify(String qualifiedName) {
    int loc = qualifiedName.lastIndexOf(".");
    return loc < 0 ? qualifiedName : qualifiedName.substring(loc + 1);
  }

  private static String cleanAlias(String alias) {
    char[] chars = alias.toCharArray();
    if (chars.length == 0 || Character.isLetter(chars[0])) {
      return alias;
    }

    for (int i = 1; i < chars.length; i++) {
      if (Character.isLetter(chars[i])) {
        return alias.substring(i);
      }
    }

    return alias;
  }
}
