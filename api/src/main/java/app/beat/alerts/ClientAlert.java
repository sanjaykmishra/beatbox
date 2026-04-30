package app.beat.alerts;

import java.time.Instant;
import java.util.UUID;

public record ClientAlert(
    UUID id,
    UUID clientId,
    UUID workspaceId,
    String alertType,
    String severity,
    int count,
    String badgeLabel,
    String cardTitle,
    String cardSubtitle,
    String cardActionLabel,
    String cardActionPath,
    Instant computedAt) {}
