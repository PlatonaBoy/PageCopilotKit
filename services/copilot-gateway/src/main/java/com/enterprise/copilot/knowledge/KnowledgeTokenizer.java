package com.enterprise.copilot.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixed Chinese/Latin tokenizer for lexical retrieval.
 *
 * <p>Chinese text has no word delimiters, so whitespace tokenization would turn "退货政策是什么" into a
 * single term that only ever matches itself. CJK runs are therefore indexed as character bigrams —
 * the standard segmenter-free approach — which lets "退货政策" match a document containing "退货" and
 * "政策" while still being far more precise than unigrams. A CJK run of a single character has no
 * bigram, so it is kept as-is rather than dropped.
 *
 * <p>Latin runs are lowercased words; single letters are dropped as noise, but digits are kept
 * because identifiers and amounts are exactly what users search for.
 */
public final class KnowledgeTokenizer {

  private KnowledgeTokenizer() {}

  public static List<String> tokenize(String text) {
    List<String> tokens = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return tokens;
    }

    int i = 0;
    int length = text.length();
    while (i < length) {
      char ch = text.charAt(i);
      if (isCjk(ch)) {
        int start = i;
        while (i < length && isCjk(text.charAt(i))) {
          i += 1;
        }
        addCjkTokens(tokens, text, start, i);
      } else if (Character.isLetterOrDigit(ch)) {
        int start = i;
        while (i < length && Character.isLetterOrDigit(text.charAt(i)) && !isCjk(text.charAt(i))) {
          i += 1;
        }
        String word = text.substring(start, i).toLowerCase(java.util.Locale.ROOT);
        if (word.length() > 1 || Character.isDigit(ch)) {
          tokens.add(word);
        }
      } else {
        i += 1;
      }
    }
    return tokens;
  }

  private static void addCjkTokens(List<String> tokens, String text, int start, int end) {
    if (end - start == 1) {
      tokens.add(text.substring(start, end));
      return;
    }
    for (int i = start; i + 2 <= end; i += 1) {
      tokens.add(text.substring(i, i + 2));
    }
  }

  /** CJK unified ideographs plus the common extension and compatibility blocks. */
  public static boolean isCjk(char ch) {
    return (ch >= 0x4E00 && ch <= 0x9FFF)
        || (ch >= 0x3400 && ch <= 0x4DBF)
        || (ch >= 0xF900 && ch <= 0xFAFF);
  }
}
