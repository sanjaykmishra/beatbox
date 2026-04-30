package app.beat.outlet;

import java.time.Instant;
import java.util.UUID;

public record Outlet(
    UUID id,
    String domain,
    String name,
    int tier,
    String tierSource,
    Integer domainAuthority,
    Long estimatedMonthlyVisits,
    String country,
    String language,
    Instant createdAt,
    Instant updatedAt) {}
