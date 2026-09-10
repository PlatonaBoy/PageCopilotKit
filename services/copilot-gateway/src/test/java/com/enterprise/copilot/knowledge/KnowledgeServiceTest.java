package com.enterprise.copilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeServiceTest {

  @Test
  void returnsTheWholeChunkWhenItFitsTheSnippetBudget() {
    assertThat(KnowledgeService.excerpt("短内容", Set.of("内容"), 100)).isEqualTo("短内容");
  }

  @Test
  void centresTheExcerptOnTheMatchInsteadOfTheChunkHead() {
    String content = "前言".repeat(200) + "退款在 5 个工作日内到账" + "附录".repeat(200);

    String excerpt = KnowledgeService.excerpt(content, Set.of("退款"), 120);

    assertThat(excerpt).contains("退款在 5 个工作日内到账");
    assertThat(excerpt.length()).isLessThanOrEqualTo(122);
  }

  @Test
  void marksElisionOnBothSidesOfAnInteriorWindow() {
    String content = "开头".repeat(100) + "关键句" + "结尾".repeat(100);

    String excerpt = KnowledgeService.excerpt(content, Set.of("关键"), 80);

    assertThat(excerpt).startsWith("…").endsWith("…");
  }

  @Test
  void fallsBackToTheHeadWhenNoQueryTermIsPresentInTheChunk() {
    String content = "甲".repeat(500);

    String excerpt = KnowledgeService.excerpt(content, Set.of("乙丙"), 50);

    assertThat(excerpt).startsWith("甲").endsWith("…");
  }
}
