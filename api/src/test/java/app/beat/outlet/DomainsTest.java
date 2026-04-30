package app.beat.outlet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainsTest {

  @Test
  void extractsApexFromCommonUrls() {
    assertThat(Domains.apexFromUrl("https://www.techcrunch.com/2025/12/04/foo"))
        .contains("techcrunch.com");
    assertThat(Domains.apexFromUrl("http://wsj.com/articles/x")).contains("wsj.com");
    assertThat(Domains.apexFromUrl("https://blog.example.org")).contains("blog.example.org");
  }

  @Test
  void returnsEmptyForBadInput() {
    assertThat(Domains.apexFromUrl(null)).isEmpty();
    assertThat(Domains.apexFromUrl("")).isEmpty();
    assertThat(Domains.apexFromUrl("not a url")).isEmpty();
  }

  @Test
  void outletNameTitleCasesStem() {
    assertThat(Domains.outletNameFromDomain("techcrunch.com")).isEqualTo("Techcrunch");
    assertThat(Domains.outletNameFromDomain("nytimes.com")).isEqualTo("Nytimes");
  }
}
