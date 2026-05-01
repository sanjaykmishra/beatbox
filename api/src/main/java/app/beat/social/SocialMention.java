package app.beat.social;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One social-platform post mentioning a client. Workspace-scoped (per docs/14-multi-tenancy.md).
 *
 * <p>Schema source: migrations/V007__phase_1_5_social.sql; spec: docs/17-phase-1-5-social.md §17.1.
 */
public record SocialMention(
    UUID id,
    UUID reportId,
    UUID workspaceId,
    UUID clientId,
    String platform,
    String sourceUrl,
    String externalPostId,
    UUID authorId,
    Instant postedAt,
    String contentText,
    String contentLang,
    boolean hasMedia,
    String mediaSummary,
    List<String> mediaUrls,
    boolean isReply,
    boolean isQuote,
    String parentPostUrl,
    String threadRootUrl,
    Long likesCount,
    Long repostsCount,
    Long repliesCount,
    Long viewsCount,
    Long estimatedReach,
    String summary,
    String sentiment,
    String sentimentRationale,
    String subjectProminence,
    List<String> topics,
    Long followerCountAtPost,
    String extractionStatus,
    String extractionError,
    String extractionPromptVersion,
    String rawExtracted,
    boolean isUserEdited,
    List<String> editedFields,
    int sortOrder,
    Instant createdAt,
    Instant updatedAt) {}
