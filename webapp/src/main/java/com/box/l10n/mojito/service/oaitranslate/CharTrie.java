package com.box.l10n.mojito.service.oaitranslate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CharTrie<T extends CharTrie.Term> {

  private final Node<T> root = new Node<>(null, new ArrayList<>(), new HashMap<>());

  boolean caseSensitive;

  public CharTrie(boolean caseSensitive) {
    this.caseSensitive = caseSensitive;
  }

  public void addTerm(T term) {
    // Locale not specified for simplicity — toLowerCase() may behave incorrectly in languages like
    // Turkish
    // This is fine as long as source strings are in English.
    String source = caseSensitive ? term.text() : term.text().toLowerCase();
    Node<T> cur = root;
    for (char c : source.toCharArray()) {
      cur = cur.child().computeIfAbsent(c, k -> new Node<>(c, new ArrayList<>(), new HashMap<>()));
    }
    cur.terms().add(term);
  }

  public Set<T> findTerms(String text) {
    Set<T> terms = new HashSet<>();
    for (Match<T> match : findMatches(text)) {
      terms.add(match.term());
    }
    return terms;
  }

  public List<Match<T>> findMatches(String text) {
    List<Match<T>> matches = new ArrayList<>();
    String originalText = text;
    if (!caseSensitive) {
      text = text.toLowerCase();
    }

    for (int i = 0; i < text.length(); i++) {
      Node<T> cur = root;
      for (int j = i; j < text.length(); j++) {
        cur = cur.child().get(text.charAt(j));
        if (cur == null) break;
        if (!cur.terms().isEmpty()) {
          String matchedText = originalText.substring(i, j + 1);
          for (T term : cur.terms()) {
            matches.add(new Match<>(term, i, j + 1, matchedText));
          }
        }
      }
    }
    return matches;
  }

  public interface Term {
    String text();
  }

  public record Match<T>(T term, int startIndex, int endIndex, String matchedText) {}

  private record Node<T>(Character c, List<T> terms, Map<Character, Node<T>> child) {}
}
