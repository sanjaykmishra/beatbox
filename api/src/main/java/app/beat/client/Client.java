package app.beat.client;

import java.time.Instant;
import java.util.UUID;

public record Client(
    UUID id,
    UUID workspaceId,
    String name,
    String logoUrl,
    String primaryColor,
    String notes,
    String defaultCadence,
    Instant setupDismissedAt,
    Instant createdAt,
    Instant updatedAt) {}
