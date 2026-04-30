package app.beat.llm;

import java.time.LocalDate;
import java.util.List;

public record ExtractionResult(
    String headline,
    String subheadline,
    String author,
    LocalDate publishDate,
    String lede,
    String summary,
    String keyQuote,
    String sentiment, // positive|neutral|negative|mixed
    String sentimentRationale,
    String subjectProminence, // feature|mention|passing
    List<String> topics) {}
