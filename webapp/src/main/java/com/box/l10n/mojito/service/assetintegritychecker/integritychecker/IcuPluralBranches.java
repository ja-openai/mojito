package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.MessagePattern.ArgType;
import com.ibm.icu.text.MessagePattern.Part;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/** Matches corresponding ICU branches before comparing the HTML in their message bodies. */
final class IcuPluralBranches {
  private static final Pattern PLURAL =
      Pattern.compile(",\\s*(?:plural|selectordinal)\\s*,", Pattern.CASE_INSENSITIVE);
  private static final Set<String> CATEGORIES =
      Set.of("zero", "one", "two", "few", "many", "other");

  private record Argument(String name, ArgType type) {}

  // tagIndex is the number of enclosing tags before this argument in message order.
  private record Choice(int tagIndex, Map<String, Message> branches) {
    void checkOrder(List<String> stack, Set<String> closingTags) {
      var arms = branches.values().iterator();
      List<String> common = new ArrayList<>(stack);
      arms.next().checkOrder(common, closingTags); // Every parsed ICU choice has an other arm.
      while (arms.hasNext()) {
        List<String> branchStack = new ArrayList<>(stack);
        arms.next().checkOrder(branchStack, closingTags);
        // A later close must match the top on every path. Keeping the common top
        // avoids enumerating combinations of sequential choices.
        int i = common.size();
        int j = branchStack.size();
        while (i > 0 && j > 0 && common.get(i - 1).equals(branchStack.get(j - 1))) {
          i--;
          j--;
        }
        common.subList(0, i).clear();
      }
      stack.clear();
      stack.addAll(common);
    }
  }

  private record Message(List<String> tags, Map<Argument, Choice> arguments) {
    void checkOrder(List<String> stack, Set<String> closingTags) {
      int cursor = 0;
      for (Choice choice : arguments.values()) {
        HtmlTagIntegrityChecker.checkOrder(
            tags.subList(cursor, choice.tagIndex), stack, closingTags);
        choice.checkOrder(stack, closingTags);
        cursor = choice.tagIndex;
      }
      HtmlTagIntegrityChecker.checkOrder(tags.subList(cursor, tags.size()), stack, closingTags);
    }
  }

  static boolean compareTags(
      String source,
      String target,
      Pattern tagPattern,
      Set<String> closingTags,
      BiConsumer<List<String>, List<String>> check) {
    if (source == null
        || target == null
        || source.indexOf('<') < 0
        || !PLURAL.matcher(source).find()) {
      return false;
    }
    Message left;
    Message right;
    try {
      left = read(source, tagPattern);
      right = read(target, tagPattern);
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      // HTML_TAG also runs on non-ICU catalogs. Leave syntax errors to the configured ICU checker.
      return false;
    }
    if (left.arguments.isEmpty() || !compatible(left, right)) {
      return false;
    }
    compare(left, right, check);
    right.checkOrder(new ArrayList<>(), closingTags);
    return true;
  }

  private static Message read(String text, Pattern tagPattern) {
    // Match complete tags before splitting branches so ICU expressions inside attributes
    // remain part of the exact tag spelling checked by HTML_TAG.
    Deque<MatchResult> tags = new ArrayDeque<>(tagPattern.matcher(text).results().toList());
    return read(new MessagePattern(text), 0, tags);
  }

  private static void collectTags(Deque<MatchResult> remaining, int before, List<String> tags) {
    while (!remaining.isEmpty() && remaining.peek().start() < before) {
      tags.add(remaining.remove().group());
    }
  }

  private static Message read(MessagePattern pattern, int start, Deque<MatchResult> remaining) {
    int end = pattern.getLimitPartIndex(start);
    List<String> tags = new ArrayList<>();
    Map<Argument, Choice> arguments = new LinkedHashMap<>();
    for (int i = start + 1; i < end; i++) {
      Part part = pattern.getPart(i);
      if (part.getType() != Part.Type.ARG_START) {
        continue;
      }
      int limit = pattern.getLimitPartIndex(i);
      ArgType type = part.getArgType();
      if (type.hasPluralStyle() || type == ArgType.SELECT) {
        String name = pattern.getSubstring(pattern.getPart(i + 1));
        Map<String, Message> branches = new LinkedHashMap<>();
        collectTags(remaining, part.getIndex(), tags);
        if (arguments.put(new Argument(name, type), new Choice(tags.size(), branches)) != null) {
          // A translation can reorder repeated arguments with different markup. Their
          // names and types do not uniquely identify corresponding occurrences.
          throw new IllegalArgumentException("Ambiguous repeated argument");
        }
        String key = null;
        for (int j = i + 2; j < limit; j++) {
          Part branch = pattern.getPart(j);
          if (branch.getType() == Part.Type.ARG_SELECTOR) {
            key = pattern.getSubstring(branch);
          } else if (branch.getType() == Part.Type.MSG_START) {
            collectTags(remaining, branch.getLimit(), tags);
            if (branches.put(key, read(pattern, j, remaining)) != null) {
              throw new IllegalArgumentException("Repeated selector");
            }
            j = pattern.getLimitPartIndex(j);
          }
        }
      }
      i = limit;
    }
    collectTags(remaining, pattern.getPart(end).getIndex(), tags);
    return new Message(tags, arguments);
  }

  // Only locale-dependent plural keywords can vary. For unrelated message structures,
  // retain the ordinary whole-string tag check.
  private static boolean compatible(Message source, Message target) {
    if (!source.arguments.keySet().equals(target.arguments.keySet())) {
      return false;
    }
    for (var entry : source.arguments.entrySet()) {
      Argument arg = entry.getKey();
      var left = entry.getValue().branches;
      var right = target.arguments.get(arg).branches;
      for (String key : left.keySet()) {
        if (!right.containsKey(key) && !(arg.type.hasPluralStyle() && CATEGORIES.contains(key))) {
          return false;
        }
      }
      for (var branch : right.entrySet()) {
        String key = branch.getKey();
        if (!left.containsKey(key) && !(arg.type.hasPluralStyle() && CATEGORIES.contains(key))) {
          return false;
        }
        if (!compatible(left.getOrDefault(key, left.get("other")), branch.getValue())) {
          return false;
        }
      }
    }
    return true;
  }

  private static void compare(
      Message source, Message target, BiConsumer<List<String>, List<String>> check) {
    check.accept(source.tags, target.tags);
    for (var entry : target.arguments.entrySet()) {
      var left = source.arguments.get(entry.getKey()).branches;
      for (var branch : entry.getValue().branches.entrySet()) {
        compare(left.getOrDefault(branch.getKey(), left.get("other")), branch.getValue(), check);
      }
    }
  }

  private IcuPluralBranches() {}
}
