package app.beat.llm;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code llm_batch_jobs} — see migrations/V010 and {@link AnthropicBatchClient}. */
public record LlmBatchJob(
    UUID id,
    UUID workspaceId,
    String feature,
    UUID targetId,
    String anthropicBatchId,
    String status,
    int requestCount,
    int succeededCount,
    int erroredCount,
    String metadataJson,
    String lastError,
    Instant submittedAt,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt) {}
