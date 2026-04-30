package app.beat.coverage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CoverageItem(
    UUID id,
    UUID reportId,
    String sourceUrl,
    UUID outletId,
    UUID authorId,
    String headline,
    String subheadline,
    LocalDate publishDate,
    String lede,
    String summary,
    String keyQuote,
    String sentiment,
    String sentimentRationale,
    String subjectProminence,
    List<String> topics,
    Long estimatedReach,
    Integer tierAtExtraction,
    String screenshotUrl,
    String extractionStatus,
    String extractionError,
    String extractionPromptVersion,
    boolean isUserEdited,
    List<String> editedFields,
    int sortOrder,
    Instant createdAt,
    Instant updatedAt) {}
