package com.enterprise.copilot.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into overlapping retrieval chunks.
 *
 * <p>Paragraph boundaries are respected wherever possible, because a chunk that starts mid-sentence
 * reads badly as a citation. Adjacent chunks overlap so a fact that straddles a boundary is still
 * retrievable from at least one of them.
 */
public final class KnowledgeChunker {

  private KnowledgeChunker() {}

  public static List<String> chunk(String content, int targetChars, int overlapChars) {
    int target = Math.max(targetChars, 80);
    int overlap = Math.min(Math.max(overlapChars, 0), target / 2);

    String normalized = content == null ? "" : content.replace("\r\n", "\n").strip();
    if (normalized.isEmpty()) {
      return List.of();
    }
    if (normalized.length() <= target) {
      return List.of(normalized);
    }

    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : splitParagraphs(normalized, target)) {
      if (!current.isEmpty() && current.length() + paragraph.length() + 2 > target) {
        chunks.add(current.toString().strip());
        String carry = tail(current.toString(), overlap);
        current.setLength(0);
        if (!carry.isBlank()) {
          current.append(carry).append("\n\n");
        }
      }
      current.append(paragraph).append("\n\n");
    }
    if (!current.toString().isBlank()) {
      chunks.add(current.toString().strip());
    }
    return chunks;
  }

  /** Paragraphs, with any paragraph longer than the target hard-split so a chunk stays bounded. */
  private static List<String> splitParagraphs(String content, int target) {
    List<String> out = new ArrayList<>();
    for (String paragraph : content.split("\n{2,}")) {
      String trimmed = paragraph.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      while (trimmed.length() > target) {
        int cut = preferredCut(trimmed, target);
        out.add(trimmed.substring(0, cut).strip());
        trimmed = trimmed.substring(cut).strip();
      }
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /** Prefers a sentence end near the limit so a hard split does not land mid-word. */
  private static int preferredCut(String text, int target) {
    int floor = Math.max(target / 2, 1);
    for (int i = target; i > floor; i -= 1) {
      char ch = text.charAt(i - 1);
      if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' || ch == '.') {
        return i;
      }
    }
    return target;
  }

  private static String tail(String text, int overlap) {
    if (overlap <= 0 || text.length() <= overlap) {
      return "";
    }
    return text.substring(text.length() - overlap).strip();
  }
}
