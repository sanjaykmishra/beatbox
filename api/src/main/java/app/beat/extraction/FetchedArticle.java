package app.beat.extraction;

import java.time.LocalDate;

public record FetchedArticle(
    String url,
    String cleanText,
    String headline,
    String byline,
    LocalDate publishDate,
    String outletName,
    String fetcher /* which strategy returned this */) {}
