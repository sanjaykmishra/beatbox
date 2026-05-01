package app.beat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Per-feature handler that processes the JSONL results of a finished Anthropic batch and writes
 * outputs back to the originating rows. The {@link BatchPoller} dispatches to a handler whose
 * {@link #feature()} matches {@link LlmBatchJob#feature()}.
 *
 * <p>Phase 3 Part 2 will register handlers for {@code journalist_ranking} and {@code pitch_draft};
 * this interface ships before either consumer exists so the wiring is obvious.
 */
public interface BatchResultHandler {

  /**
   * Stable feature label that the consumer used when {@link AnthropicBatchClient#submit
   * submitting}.
   */
  String feature();

  /**
   * Process the results returned by Anthropic for a finished batch.
   *
   * @param job the batch job row (workspace context, target id, metadata)
   * @param results one JSON node per request line in the batch's results file
   */
  void handle(LlmBatchJob job, List<JsonNode> results);
}
