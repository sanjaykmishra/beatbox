package app.beat.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Report(
    UUID id,
    UUID clientId,
    UUID workspaceId,
    UUID templateId,
    String title,
    LocalDate periodStart,
    LocalDate periodEnd,
    String status,
    String executiveSummary,
    boolean executiveSummaryEdited,
    String pdfUrl,
    String shareToken,
    Instant shareTokenExpiresAt,
    Instant generatedAt,
    String failureReason,
    UUID createdByUserId,
    Instant createdAt,
    Instant updatedAt) {}
