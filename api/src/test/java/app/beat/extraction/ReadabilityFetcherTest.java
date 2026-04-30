package app.beat.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadabilityFetcherTest {

  private static final String SAMPLE_HTML =
      """
      <!doctype html>
      <html>
      <head>
        <meta property="og:title" content="Acme Corp raises $30M Series B led by Sequoia"/>
        <meta property="og:site_name" content="TechCrunch"/>
        <meta name="author" content="Sarah Perez"/>
        <meta property="article:published_time" content="2025-12-04T14:23:00Z"/>
      </head>
      <body>
        <header><nav>nav junk</nav></header>
        <article>
          <h1>Acme Corp raises $30M Series B led by Sequoia</h1>
          <p>Acme Corp announced today that it has raised $30 million in a Series B round led by Sequoia Capital, with participation from existing investors. The funding will be used to accelerate product development of its AI-driven workflow tools and expand the engineering team to support enterprise customers across financial services and healthcare.</p>
          <p>The round brings the company's total funding to $50 million since its founding in 2023. CEO Jane Doe said the new capital will help Acme triple its enterprise customer base over the next 18 months.</p>
        </article>
        <footer>copyright 2025</footer>
      </body>
      </html>
      """;

  @Test
  void parsesHeadlineByLineDateAndOutlet() {
    var f = new ReadabilityFetcher();
    var got = f.parseHtml("https://techcrunch.com/2025/12/04/acme-series-b", SAMPLE_HTML);
    assertThat(got).isPresent();
    var a = got.get();
    assertThat(a.headline()).isEqualTo("Acme Corp raises $30M Series B led by Sequoia");
    assertThat(a.byline()).isEqualTo("Sarah Perez");
    assertThat(a.outletName()).isEqualTo("TechCrunch");
    assertThat(a.publishDate()).isNotNull();
    assertThat(a.publishDate().toString()).isEqualTo("2025-12-04");
    assertThat(a.cleanText()).contains("Sequoia");
    assertThat(a.cleanText()).doesNotContain("nav junk");
    assertThat(a.cleanText()).doesNotContain("copyright 2025");
    assertThat(a.fetcher()).isEqualTo("readability");
  }

  @Test
  void returnsEmptyOnTooShortContent() {
    var f = new ReadabilityFetcher();
    var got = f.parseHtml("https://x.example", "<html><body><p>too short</p></body></html>");
    assertThat(got).isEmpty();
  }
}
