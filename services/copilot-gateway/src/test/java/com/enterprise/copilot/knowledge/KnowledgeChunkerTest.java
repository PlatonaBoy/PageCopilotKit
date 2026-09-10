package com.enterprise.copilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTest {

  @Test
  void keepsAShortDocumentWhole() {
    assertThat(KnowledgeChunker.chunk("短文档内容", 700, 120)).containsExactly("短文档内容");
  }

  @Test
  void splitsOnParagraphBoundariesRatherThanMidSentence() {
    String content = "第一段内容。\n\n" + "第二段内容。".repeat(30);

    List<String> chunks = KnowledgeChunker.chunk(content, 120, 0);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks.get(0)).startsWith("第一段内容。");
  }

  @Test
  void overlapsAdjacentChunksSoBoundaryFactsStayRetrievable() {
    String content = "AAAA。\n\n" + "BBBB。".repeat(40);

    List<String> withoutOverlap = KnowledgeChunker.chunk(content, 100, 0);
    List<String> withOverlap = KnowledgeChunker.chunk(content, 100, 40);

    assertThat(String.join("", withOverlap).length())
        .isGreaterThan(String.join("", withoutOverlap).length());
  }

  @Test
  void hardSplitsAParagraphLongerThanTheTarget() {
    String content = "X".repeat(1000);

    List<String> chunks = KnowledgeChunker.chunk(content, 200, 0);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(200));
  }

  @Test
  void prefersASentenceEndWhenHardSplitting() {
    String content = "甲".repeat(150) + "。" + "乙".repeat(150);

    List<String> chunks = KnowledgeChunker.chunk(content, 200, 0);

    assertThat(chunks.get(0)).endsWith("。");
  }

  @Test
  void returnsNothingForBlankContent() {
    assertThat(KnowledgeChunker.chunk("   \n\n  ", 700, 120)).isEmpty();
  }
}
