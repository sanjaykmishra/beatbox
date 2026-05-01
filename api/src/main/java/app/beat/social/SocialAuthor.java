package app.beat.social;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A social-platform author identity (per platform + handle). Global table — no workspace_id. Linked
 * to {@code authors} via {@code linked_author_id} when manually merged.
 *
 * <p>Spec: docs/17-phase-1-5-social.md §17.1.
 */
public record SocialAuthor(
    UUID id,
    String platform,
    String handle,
    String displayName,
    String bio,
    Long followerCount,
    String profileUrl,
    String avatarUrl,
    boolean isVerified,
    UUID linkedAuthorId,
    List<String> topicTags,
    Instant lastSeenAt,
    Instant createdAt,
    Instant updatedAt) {}
