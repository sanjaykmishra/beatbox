package app.beat.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionTokensTest {

  @Test
  void generateProducesUniqueTokens() {
    String a = SessionTokens.generate();
    String b = SessionTokens.generate();
    assertThat(a).isNotEqualTo(b);
    assertThat(a).hasSizeGreaterThanOrEqualTo(40);
    assertThat(a).doesNotContain("=");
  }

  @Test
  void hashIsDeterministicAndHidesToken() {
    String token = "abc123";
    String hash1 = SessionTokens.hash(token);
    String hash2 = SessionTokens.hash(token);
    assertThat(hash1).isEqualTo(hash2);
    assertThat(hash1).hasSize(64);
    assertThat(hash1).doesNotContain(token);
  }
}
