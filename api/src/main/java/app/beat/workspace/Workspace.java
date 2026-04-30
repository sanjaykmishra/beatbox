package app.beat.workspace;

import java.time.Instant;
import java.util.UUID;

public record Workspace(
    UUID id,
    String name,
    String slug,
    String logoUrl,
    String primaryColor,
    String plan,
    int planLimitClients,
    int planLimitReportsMonthly,
    String stripeCustomerId,
    String stripeSubscriptionId,
    Instant trialEndsAt,
    UUID defaultTemplateId,
    Instant createdAt,
    Instant updatedAt) {}
