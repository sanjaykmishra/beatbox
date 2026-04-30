package app.beat.social;

import app.beat.auth.User;
import app.beat.auth.UserRepository;
import app.beat.billing.EmailService;
import app.beat.client.Client;
import app.beat.workspace.WorkspaceMemberRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends email notifications when a post transitions to {@code internal_review}. Phase 1.5 §17.4's
 * full client-approval portal lands in Week 5; this is the lightweight interim so the "Submit for
 * review" button does something useful before then.
 *
 * <p>Internal review only — recipients are workspace members (owners + members, never viewers)
 * other than the submitter. Client review notifications still wait for the portal build.
 */
@Service
public class PostReviewNotifier {

  private static final Logger log = LoggerFactory.getLogger(PostReviewNotifier.class);

  private final WorkspaceMemberRepository members;
  private final UserRepository users;
  private final EmailService email;
  private final String appBaseUrl;

  public PostReviewNotifier(
      WorkspaceMemberRepository members,
      UserRepository users,
      EmailService email,
      @Value("${beat.app.base-url:http://localhost:5173}") String appBaseUrl) {
    this.members = members;
    this.users = users;
    this.email = email;
    this.appBaseUrl = stripTrailingSlash(appBaseUrl);
  }

  /**
   * Fire-and-forget: never throws. Skips entirely when SMTP isn't configured. Best-effort even if
   * we can't resolve the actor — the email still goes out without a name.
   */
  public void notifyInternalReview(OwnedPost post, Client client, UUID submitterUserId) {
    if (!email.isConfigured()) {
      log.debug("internal_review notify: email not configured; skipping for post {}", post.id());
      return;
    }
    List<String> recipients =
        members.notificationEmailsForWorkspace(post.workspaceId(), submitterUserId);
    if (recipients.isEmpty()) {
      log.debug("internal_review notify: no recipients for workspace {}", post.workspaceId());
      return;
    }
    String submitterName =
        users
            .findById(submitterUserId)
            .map(User::name)
            .filter(s -> s != null && !s.isBlank())
            .orElse("A teammate");
    String subject =
        String.format("%s submitted a %s post for review", submitterName, client.name());
    String html = renderHtml(post, client, submitterName);
    for (String to : recipients) {
      email.sendTransactional(to, subject, html);
    }
  }

  private String renderHtml(OwnedPost post, Client client, String submitterName) {
    String title = post.title() == null || post.title().isBlank() ? "Untitled post" : post.title();
    String snippet = excerpt(post.primaryContentText(), 280);
    String calendarUrl = appBaseUrl + "/calendar";
    String escapedTitle = escape(title);
    String escapedClient = escape(client.name());
    String escapedSubmitter = escape(submitterName);
    String escapedSnippet = escape(snippet);
    StringBuilder b = new StringBuilder(800);
    b.append("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',");
    b.append(
        "Helvetica,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#0b0f19;\">");
    b.append(
        "<p style=\"font-size:13px;color:#6b7280;margin:0 0 8px;\">Beat — internal review</p>");
    b.append("<h1 style=\"font-size:18px;margin:0 0 8px;letter-spacing:-0.01em;\">")
        .append(escapedSubmitter)
        .append(" wants a quick review</h1>");
    b.append("<p style=\"font-size:14px;color:#374151;margin:0 0 16px;\">")
        .append(escapedClient)
        .append(" · <strong>")
        .append(escapedTitle)
        .append("</strong></p>");
    if (!snippet.isEmpty()) {
      b.append(
              "<blockquote style=\"border-left:3px solid #e5e7eb;padding:4px 12px;margin:0 0 20px;"
                  + "color:#4b5563;font-size:13px;line-height:1.55;\">")
          .append(escapedSnippet)
          .append("</blockquote>");
    }
    b.append("<p style=\"margin:0 0 24px;\"><a href=\"")
        .append(calendarUrl)
        .append(
            "\" style=\"display:inline-block;background:#0b0f19;color:#fff;text-decoration:none;"
                + "padding:10px 18px;border-radius:8px;font-size:14px;font-weight:500;\">Open the calendar →</a></p>");
    b.append(
        "<p style=\"font-size:12px;color:#9ca3af;margin:0;\">You're getting this because you're a "
            + "member of the workspace. The full client-approval portal ships in Phase 1.5 Week 5.</p>");
    b.append("</div>");
    return b.toString();
  }

  private static String stripTrailingSlash(String s) {
    if (s == null) return "";
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String excerpt(String s, int max) {
    if (s == null) return "";
    String t = s.strip();
    return t.length() <= max ? t : t.substring(0, max).strip() + "…";
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
