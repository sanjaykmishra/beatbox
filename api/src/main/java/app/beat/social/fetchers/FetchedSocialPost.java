package app.beat.social.fetchers;

import java.time.Instant;
import java.util.List;

/**
 * Platform-agnostic shape returned by {@link SocialPostFetcher}. Mirrors the fields the LLM
 * extraction prompt (prompts/social-extraction-v1.md) needs as inputs, plus the pre-LLM data
 * persisted on the social_mentions row before the LLM runs.
 */
public record FetchedSocialPost(
    String platform,
    String externalPostId,
    /* author */
    String authorHandle,
    String authorDisplayName,
    String authorBio,
    Long authorFollowerCount,
    String authorProfileUrl,
    String authorAvatarUrl,
    boolean authorVerified,
    /* post */
    Instant postedAt,
    String contentText,
    String contentLang,
    boolean isReply,
    boolean isQuote,
    String parentPostUrl,
    String parentPostText,
    String threadRootUrl,
    boolean hasMedia,
    List<String> mediaUrls,
    List<String> mediaDescriptions,
    /* engagement */
    Long likesCount,
    Long repostsCount,
    Long repliesCount,
    Long viewsCount) {}
