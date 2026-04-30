-- Beat — V003: idempotency cache for LLM extraction.
-- Per docs/05-llm-prompts.md cost controls:
--   "Idempotency cache: (article_content_hash, prompt_version) -> extracted_json.
--    If a customer re-pastes the same article URL into a new report, we hit cache."

CREATE TABLE extraction_cache (
    content_hash    TEXT NOT NULL,
    prompt_version  TEXT NOT NULL,
    model           TEXT NOT NULL,
    json_result     JSONB NOT NULL,
    input_tokens    INT,
    output_tokens   INT,
    cost_usd        NUMERIC(10, 6),
    hit_count       INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (content_hash, prompt_version)
);

CREATE INDEX idx_extraction_cache_last_used ON extraction_cache(last_used_at);
