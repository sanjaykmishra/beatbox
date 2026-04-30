package app.beat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AnthropicClientCostTest {

  @Test
  void sonnetCostMatchesPublishedRates() {
    BigDecimal cost = AnthropicClient.costFor("claude-sonnet-4", 1_000_000, 0);
    assertThat(cost).isEqualByComparingTo(new BigDecimal("3.00"));
    BigDecimal cost2 = AnthropicClient.costFor("claude-sonnet-4", 0, 1_000_000);
    assertThat(cost2).isEqualByComparingTo(new BigDecimal("15.00"));
  }

  @Test
  void opusIsMoreExpensive() {
    BigDecimal sonnet = AnthropicClient.costFor("claude-sonnet-4", 1000, 1000);
    BigDecimal opus = AnthropicClient.costFor("claude-opus", 1000, 1000);
    assertThat(opus).isGreaterThan(sonnet);
  }

  @Test
  void truncatesByCharacterApproxTokenBudget() {
    String text = "x".repeat(100_000);
    String out = AnthropicClient.truncateForTokenBudget(text, 1000);
    assertThat(out).hasSize(4000);
  }
}
