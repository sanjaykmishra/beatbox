package app.beat.llm;

import java.math.BigDecimal;

public record LlmCallLog(
    String promptVersion,
    String model,
    int inputTokens,
    int outputTokens,
    BigDecimal costUsd,
    long durationMs,
    String outcome) {}
