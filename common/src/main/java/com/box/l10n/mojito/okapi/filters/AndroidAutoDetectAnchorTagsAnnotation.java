package com.box.l10n.mojito.okapi.filters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.okapi.common.annotation.IAnnotation;
import net.sf.okapi.common.resource.Code;
import net.sf.okapi.common.resource.TextFragment;

/** Preserves real source anchor elements so auto mode can distinguish them from escaped text. */
public class AndroidAutoDetectAnchorTagsAnnotation implements IAnnotation {

  private static final AndroidXMLEncoder ANDROID_XML_ENCODER = new AndroidXMLEncoder(false);

  private final List<Code> anchorCodes;

  private AndroidAutoDetectAnchorTagsAnnotation(List<Code> anchorCodes) {
    this.anchorCodes = anchorCodes;
  }

  public static AndroidAutoDetectAnchorTagsAnnotation from(TextFragment source) {
    List<Code> anchorCodes =
        source.getCodes().stream()
            .filter(code -> "a".equals(code.getType()))
            .filter(
                code ->
                    code.getTagType() == TextFragment.TagType.OPENING
                        || code.getTagType() == TextFragment.TagType.CLOSING)
            .map(Code::clone)
            .toList();

    return anchorCodes.isEmpty() ? null : new AndroidAutoDetectAnchorTagsAnnotation(anchorCodes);
  }

  public TextFragment restoreAnchorCodes(String translation) {
    Map<String, Deque<Code>> openingCodesByData = new LinkedHashMap<>();
    Map<Integer, Code> closingCodesById = new LinkedHashMap<>();
    for (Code code : anchorCodes) {
      if (code.getTagType() == TextFragment.TagType.OPENING) {
        openingCodesByData.computeIfAbsent(code.getData(), ignored -> new ArrayDeque<>()).add(code);
      } else {
        closingCodesById.put(code.getId(), code);
      }
    }

    TextFragment restored = new TextFragment();
    Deque<Code> openCodes = new ArrayDeque<>();
    int cursor = 0;
    while (cursor < translation.length()) {
      Match nextOpening = findNextOpening(translation, cursor, openingCodesByData);
      Code expectedClosing =
          openCodes.isEmpty() ? null : closingCodesById.get(openCodes.peek().getId());
      int closingIndex =
          expectedClosing == null ? -1 : translation.indexOf(expectedClosing.getData(), cursor);

      if (nextOpening == null && closingIndex < 0) {
        break;
      }

      boolean useClosing =
          closingIndex >= 0 && (nextOpening == null || closingIndex < nextOpening.index());
      int matchIndex = useClosing ? closingIndex : nextOpening.index();
      appendText(restored, translation.substring(cursor, matchIndex));

      if (useClosing) {
        restored.append(expectedClosing.clone());
        openCodes.pop();
        cursor = matchIndex + expectedClosing.getData().length();
      } else {
        Code openingCode = openingCodesByData.get(nextOpening.data()).removeFirst();
        restored.append(openingCode.clone());
        openCodes.push(openingCode);
        cursor = matchIndex + nextOpening.data().length();
      }
    }
    appendText(restored, translation.substring(cursor));

    boolean hasUnusedOpeningCodes =
        openingCodesByData.values().stream().anyMatch(codes -> !codes.isEmpty());
    if (!openCodes.isEmpty() || hasUnusedOpeningCodes) {
      return new TextFragment(translation);
    }
    return restored;
  }

  private void appendText(TextFragment restored, String text) {
    // Text introduced while restoring codes bypasses the Android encoder's normal text path.
    restored.append(ANDROID_XML_ENCODER.escapeSingleQuotes(text));
  }

  private Match findNextOpening(
      String translation, int cursor, Map<String, Deque<Code>> openingCodesByData) {
    List<Match> matches = new ArrayList<>();
    for (Map.Entry<String, Deque<Code>> entry : openingCodesByData.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        int index = translation.indexOf(entry.getKey(), cursor);
        if (index >= 0) {
          matches.add(new Match(index, entry.getKey()));
        }
      }
    }
    return matches.stream()
        .min((left, right) -> Integer.compare(left.index(), right.index()))
        .orElse(null);
  }

  private record Match(int index, String data) {}
}
