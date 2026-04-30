package app.beat.auth;

import java.time.Instant;
import java.util.UUID;

public record User(
    UUID id,
    String email,
    String passwordHash,
    String name,
    Instant emailVerifiedAt,
    Instant lastLoginAt,
    Instant createdAt,
    Instant updatedAt) {}
