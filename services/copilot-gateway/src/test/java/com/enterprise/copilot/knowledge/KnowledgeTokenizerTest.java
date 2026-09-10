package com.enterprise.copilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeTokenizerTest {

  @Test
  void splitsChineseIntoCharacterBigrams() {
    assertThat(KnowledgeTokenizer.tokenize("退货政策")).containsExactly("退货", "货政", "政策");
  }

  @Test
  void keepsASingleChineseCharacterAsItsOwnTerm() {
    // "票" has no bigram partner; dropping it would make one-character queries unanswerable.
    assertThat(KnowledgeTokenizer.tokenize("票 invoice")).contains("票");
  }

  @Test
  void lowercasesLatinWordsAndKeepsDigits() {
    assertThat(KnowledgeTokenizer.tokenize("Refund SLA 30 days"))
        .containsExactly("refund", "sla", "30", "days");
  }

  @Test
  void dropsPunctuationAndSingleLatinLetters() {
    assertThat(KnowledgeTokenizer.tokenize("a, b. VIP!")).containsExactly("vip");
  }

  @Test
  void tokenizesMixedChineseAndLatinIndependently() {
    List<String> tokens = KnowledgeTokenizer.tokenize("VIP客户30天");

    assertThat(tokens).contains("vip", "客户", "30");
    // The Latin run must not be glued to the CJK run into one unmatchable term.
    assertThat(tokens).noneMatch(token -> token.contains("vip客"));
  }

  @Test
  void toleratesNullAndEmptyInput() {
    assertThat(KnowledgeTokenizer.tokenize(null)).isEmpty();
    assertThat(KnowledgeTokenizer.tokenize("   ")).isEmpty();
  }
}
