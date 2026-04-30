package app.beat.social;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.client.ClientRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owned posts CRUD + state machine. Covers the spec endpoints in docs/17-phase-1-5-social.md §17.2
 * except {@code regenerate-variants}, which lands when the post-variant LLM call is wired up in
 * Week 2.
 */
@RestController
@RequestMapping("/v1")
public class OwnedPostController {

  private static final List<String> VALID_PLATFORMS =
      List.of(
          "x",
          "linkedin",
          "bluesky",
          "threads",
          "instagram",
          "facebook",
          "tiktok",
          "reddit",
          "substack",
          "youtube",
          "mastodon");

  private final OwnedPostRepository posts;
  private final ClientRepository clients;
  private final ActivityRecorder activity;
  private final PostVariantService variants;
  private final PostReviewNotifier reviewNotifier;

  public OwnedPostController(
      OwnedPostRepository posts,
      ClientRepository clients,
      ActivityRecorder activity,
      PostVariantService variants,
      PostReviewNotifier reviewNotifier) {
    this.posts = posts;
    this.clients = clients;
    this.activity = activity;
    this.variants = variants;
    this.reviewNotifier = reviewNotifier;
  }

  // ===================== DTOs =====================

  public record PlatformVariantDto(String content, Integer char_count, Instant edited_at) {}

  public record PostDto(
      UUID id,
      UUID workspace_id,
      UUID client_id,
      String title,
      String primary_content_text,
      Map<String, PlatformVariantDto> platform_variants,
      List<String> target_platforms,
      Instant scheduled_for,
      String timezone,
      String status,
      String series_tag,
      UUID drafted_by_user_id,
      Instant submitted_for_review_at,
      Instant approved_at,
      Instant posted_at,
      List<UUID> asset_ids,
      Instant created_at,
      Instant updated_at) {

    static PostDto from(OwnedPost p) {
      var variants = new LinkedHashMap<String, PlatformVariantDto>();
      for (var e : p.platformVariants().entrySet()) {
        var v = e.getValue();
        variants.put(e.getKey(), new PlatformVariantDto(v.content(), v.charCount(), v.editedAt()));
      }
      return new PostDto(
          p.id(),
          p.workspaceId(),
          p.clientId(),
          p.title(),
          p.primaryContentText(),
          variants,
          p.targetPlatforms(),
          p.scheduledFor(),
          p.timezone(),
          p.status(),
          p.seriesTag(),
          p.draftedByUserId(),
          p.submittedForReviewAt(),
          p.approvedAt(),
          p.postedAt(),
          p.assetIds(),
          p.createdAt(),
          p.updatedAt());
    }
  }

  public record CreatePostRequest(
      @NotNull UUID client_id,
      @Size(max = 200) String title,
      String primary_content_text,
      List<String> target_platforms,
      Instant scheduled_for,
      String timezone,
      @Size(max = 80) String series_tag) {}

  public record UpdatePostRequest(
      @Size(max = 200) String title,
      String primary_content_text,
      Map<String, PlatformVariantDto> platform_variants,
      List<String> target_platforms,
      Instant scheduled_for,
      String timezone,
      @Size(max = 80) String series_tag,
      List<UUID> asset_ids) {}

  public record TransitionRequest(
      @Pattern(
              regexp =
                  "internal_review|client_review|approved|scheduled|posted|rejected|archived|draft")
          String to,
      @Size(max = 1000) String reason) {}

  public record ListResponse(List<PostDto> items) {}

  // ===================== Endpoints =====================

  @GetMapping("/posts")
  public ListResponse list(
      @RequestParam(required = false) UUID client_id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String series_tag,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "200") int limit,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (client_id != null) {
      clients
          .findInWorkspace(ctx.workspaceId(), client_id)
          .orElseThrow(() -> AppException.notFound("Client"));
    }
    if (platform != null) requirePlatform(platform);
    var rows =
        posts.list(ctx.workspaceId(), client_id, status, series_tag, platform, from, to, limit);
    return new ListResponse(rows.stream().map(PostDto::from).toList());
  }

  @GetMapping("/posts/{id}")
  public PostDto get(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    return PostDto.from(
        posts
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Post")));
  }

  @PostMapping("/posts")
  public ResponseEntity<PostDto> create(
      @Valid @RequestBody CreatePostRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    clients
        .findInWorkspace(ctx.workspaceId(), body.client_id())
        .orElseThrow(() -> AppException.notFound("Client"));
    if (body.target_platforms() != null) {
      body.target_platforms().forEach(OwnedPostController::requirePlatform);
    }
    OwnedPost p =
        posts.insert(
            ctx.workspaceId(),
            body.client_id(),
            body.title(),
            body.primary_content_text(),
            body.target_platforms(),
            body.scheduled_for(),
            body.timezone(),
            body.series_tag(),
            ctx.userId());
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.POST_CREATED,
        "post",
        p.id(),
        Map.of("client_id", p.clientId().toString(), "platforms", p.targetPlatforms()));
    return ResponseEntity.status(HttpStatus.CREATED).body(PostDto.from(p));
  }

  @PatchMapping("/posts/{id}")
  public PostDto update(
      @PathVariable UUID id, @Valid @RequestBody UpdatePostRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var existing =
        posts
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Post"));
    if (body.target_platforms() != null) {
      body.target_platforms().forEach(OwnedPostController::requirePlatform);
    }
    Map<String, OwnedPost.PlatformVariant> variants = null;
    if (body.platform_variants() != null) {
      variants = new LinkedHashMap<>();
      for (var e : body.platform_variants().entrySet()) {
        requirePlatform(e.getKey());
        var v = e.getValue();
        variants.put(
            e.getKey(),
            new OwnedPost.PlatformVariant(
                v.content(),
                v.char_count() != null
                    ? v.char_count()
                    : v.content() == null ? null : v.content().length(),
                v.edited_at() != null ? v.edited_at() : Instant.now()));
      }
    }
    OwnedPost updated =
        posts.update(
            id,
            body.title(),
            body.primary_content_text(),
            variants,
            body.target_platforms(),
            body.scheduled_for(),
            body.timezone(),
            body.series_tag(),
            body.asset_ids());
    var diffs = new HashMap<String, Object>();
    if (body.title() != null) diffs.put("title", true);
    if (body.primary_content_text() != null) diffs.put("primary_content_text", true);
    if (body.platform_variants() != null) diffs.put("platform_variants", true);
    if (body.scheduled_for() != null) diffs.put("scheduled_for", true);
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.POST_UPDATED, "post", existing.id(), diffs);
    return PostDto.from(updated);
  }

  @PostMapping("/posts/{id}/transitions/{transition}")
  public PostDto transition(
      @PathVariable UUID id,
      @PathVariable String transition,
      @Valid @RequestBody(required = false) TransitionRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var existing =
        posts
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Post"));
    String target = resolveTransition(transition, body);
    var allowed = OwnedPost.ALLOWED_TRANSITIONS.getOrDefault(existing.status(), java.util.Set.of());
    if (!allowed.contains(target)) {
      throw AppException.badRequest(
          "/errors/invalid-transition",
          "Invalid transition",
          "Cannot transition from " + existing.status() + " to " + target + ".");
    }
    if ("rejected".equals(target)
        && (body == null || body.reason() == null || body.reason().isBlank())) {
      throw AppException.badRequest(
          "/errors/missing-reason", "Reason required", "Rejecting a post requires a reason.");
    }
    OwnedPost moved = posts.transition(id, target, Instant.now());
    var meta = new HashMap<String, Object>();
    meta.put("from", existing.status());
    meta.put("to", target);
    if (body != null && body.reason() != null) meta.put("reason", body.reason());
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.POST_TRANSITION, "post", id, meta);
    if ("internal_review".equals(target)) {
      clients
          .findInWorkspace(ctx.workspaceId(), moved.clientId())
          .ifPresent(c -> reviewNotifier.notifyInternalReview(moved, c, ctx.userId()));
    }
    return PostDto.from(moved);
  }

  public record RegenerateVariantsRequest(List<String> platforms) {}

  public record RegenerateVariantsResponse(
      Map<String, PlatformVariantDto> variants,
      Map<String, List<String>> warnings,
      String prompt_version,
      PostDto post) {}

  @PostMapping("/posts/{id}/regenerate-variants")
  public RegenerateVariantsResponse regenerateVariants(
      @PathVariable UUID id,
      @RequestBody(required = false) RegenerateVariantsRequest body,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var post =
        posts
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Post"));
    if (!variants.isEnabled()) {
      throw AppException.badRequest(
          "/errors/llm-disabled",
          "LLM disabled",
          "ANTHROPIC_API_KEY not configured; can't generate variants.");
    }
    if (post.primaryContentText() == null || post.primaryContentText().isBlank()) {
      throw AppException.badRequest(
          "/errors/empty-master",
          "Empty master",
          "Add primary_content_text before regenerating variants.");
    }
    List<String> targets =
        body != null && body.platforms() != null && !body.platforms().isEmpty()
            ? body.platforms()
            : post.targetPlatforms();
    if (targets.isEmpty()) {
      throw AppException.badRequest(
          "/errors/no-platforms",
          "No platforms",
          "Specify target platforms before regenerating variants.");
    }
    targets.forEach(OwnedPostController::requirePlatform);
    var clientRow =
        clients
            .findInWorkspace(ctx.workspaceId(), post.clientId())
            .orElseThrow(() -> AppException.notFound("Client"));
    var outcome =
        variants.generate(clientRow, post.primaryContentText(), targets, post.seriesTag());

    // Merge new variants into existing map, preserving any user edits the model didn't touch.
    var merged = new LinkedHashMap<>(post.platformVariants());
    merged.putAll(outcome.variants());
    OwnedPost updated = posts.update(id, null, null, merged, null, null, null, null, null);

    var dtoVariants = new LinkedHashMap<String, PlatformVariantDto>();
    for (var e : outcome.variants().entrySet()) {
      var v = e.getValue();
      dtoVariants.put(e.getKey(), new PlatformVariantDto(v.content(), v.charCount(), v.editedAt()));
    }
    activity.recordUser(
        ctx.workspaceId(),
        ctx.userId(),
        EventKinds.POST_VARIANTS_REGENERATED,
        "post",
        id,
        Map.of("platforms", targets, "prompt_version", outcome.promptVersion()));
    return new RegenerateVariantsResponse(
        dtoVariants, outcome.warnings(), outcome.promptVersion(), PostDto.from(updated));
  }

  @DeleteMapping("/posts/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    var existing =
        posts
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Post"));
    posts.softDelete(id);
    activity.recordUser(
        ctx.workspaceId(), ctx.userId(), EventKinds.POST_DELETED, "post", existing.id(), Map.of());
    return ResponseEntity.noContent().build();
  }

  // ===================== Helpers =====================

  /**
   * Resolve a friendly transition name (e.g. {@code submit_for_internal_review}) to a target
   * status. Falls through to the literal target when the verb itself names a status.
   */
  private static String resolveTransition(String verb, TransitionRequest body) {
    if (body != null && body.to() != null && !body.to().isBlank()) return body.to();
    return switch (verb) {
      case "submit_for_internal_review" -> "internal_review";
      case "request_client_approval" -> "client_review";
      case "approve" -> "approved";
      case "schedule" -> "scheduled";
      case "mark_posted" -> "posted";
      case "reject" -> "rejected";
      case "archive" -> "archived";
      case "reopen" -> "draft";
      default -> verb; // assume the verb is a literal target
    };
  }

  private static void requirePlatform(String p) {
    if (!VALID_PLATFORMS.contains(p)) {
      throw AppException.badRequest(
          "/errors/invalid-platform",
          "Invalid platform",
          "Unknown platform '" + p + "'. Valid: " + VALID_PLATFORMS);
    }
  }
}
