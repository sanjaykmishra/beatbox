-- Diagnostic: dump the inputs that fed the executive summary alongside the generated text, so we
-- can tell whether the LLM hallucinated a subject-prominence claim or correctly intuited it.
--
-- Usage:
--   docker compose exec -T postgres psql -U beat -d beat < scripts/diagnose-summary.sql
--
-- Filters to clients whose name matches 'Franklin' by default. Adjust the WHERE clause if your
-- client is named differently.

\pset pager off
\pset linestyle unicode
\pset border 2

\echo '\n=== 1. Matching clients ==='
SELECT id, name, default_cadence, created_at
FROM clients
WHERE name ILIKE '%Franklin%' AND deleted_at IS NULL
ORDER BY created_at DESC;

\echo '\n=== 2. Most recent report for each matched client ==='
WITH ranked AS (
  SELECT r.*, ROW_NUMBER() OVER (PARTITION BY r.client_id ORDER BY r.created_at DESC) AS rn
  FROM reports r
  JOIN clients c ON c.id = r.client_id
  WHERE c.name ILIKE '%Franklin%' AND r.deleted_at IS NULL
)
SELECT id, client_id, status, period_start, period_end, generated_at, created_at,
       length(executive_summary) AS summary_chars
FROM ranked WHERE rn = 1;

\echo '\n=== 3. Executive summary text (full) ==='
WITH ranked AS (
  SELECT r.*, ROW_NUMBER() OVER (PARTITION BY r.client_id ORDER BY r.created_at DESC) AS rn
  FROM reports r
  JOIN clients c ON c.id = r.client_id
  WHERE c.name ILIKE '%Franklin%' AND r.deleted_at IS NULL
)
SELECT executive_summary FROM ranked WHERE rn = 1;

\echo '\n=== 4. Coverage items in that report — headline, sentiment, subject_prominence, lede ==='
WITH latest_report AS (
  SELECT r.id
  FROM reports r
  JOIN clients c ON c.id = r.client_id
  WHERE c.name ILIKE '%Franklin%' AND r.deleted_at IS NULL
  ORDER BY r.created_at DESC LIMIT 1
)
SELECT
  ci.headline,
  ci.sentiment,
  ci.subject_prominence,
  ci.tier_at_extraction AS tier,
  o.name AS outlet,
  left(ci.lede, 200) AS lede_first_200,
  left(ci.summary, 240) AS summary_first_240,
  ci.source_url
FROM coverage_items ci
LEFT JOIN outlets o ON o.id = ci.outlet_id
WHERE ci.report_id = (SELECT id FROM latest_report)
  AND ci.extraction_status = 'done'
ORDER BY tier ASC NULLS LAST, ci.created_at;

\echo '\n=== 5. Subject-prominence aggregation (the key claim the LLM made) ==='
WITH latest_report AS (
  SELECT r.id
  FROM reports r
  JOIN clients c ON c.id = r.client_id
  WHERE c.name ILIKE '%Franklin%' AND r.deleted_at IS NULL
  ORDER BY r.created_at DESC LIMIT 1
)
SELECT
  COALESCE(subject_prominence, '<null>') AS prominence,
  count(*) AS n
FROM coverage_items
WHERE report_id = (SELECT id FROM latest_report)
  AND extraction_status = 'done'
GROUP BY 1
ORDER BY n DESC;

\echo '\n=== 6. Reconstructed coverage_items_summary the LLM actually saw ==='
-- Mirrors SummaryInputs.coverageItemsSummary() so we can see exactly what the LLM had to work
-- with. Aggregates + top outlets by reach + top topics + up to 5 headlines (tier-weighted).
WITH latest_report AS (
  SELECT r.id
  FROM reports r
  JOIN clients c ON c.id = r.client_id
  WHERE c.name ILIKE '%Franklin%' AND r.deleted_at IS NULL
  ORDER BY r.created_at DESC LIMIT 1
), items AS (
  SELECT * FROM coverage_items
  WHERE report_id = (SELECT id FROM latest_report) AND extraction_status = 'done'
), counts AS (
  SELECT
    count(*) AS total,
    count(*) FILTER (WHERE tier_at_extraction = 1) AS t1,
    count(*) FILTER (WHERE tier_at_extraction = 2) AS t2,
    count(*) FILTER (WHERE tier_at_extraction = 3) AS t3,
    count(*) FILTER (WHERE sentiment = 'positive') AS pos,
    count(*) FILTER (WHERE sentiment = 'neutral')  AS neu,
    count(*) FILTER (WHERE sentiment = 'mixed')    AS mix,
    count(*) FILTER (WHERE sentiment = 'negative') AS neg
  FROM items
), outlets_top AS (
  SELECT string_agg(name, ', ' ORDER BY reach DESC) AS list FROM (
    SELECT o.name, sum(GREATEST(coalesce(i.estimated_reach, 1), 1)) AS reach
    FROM items i JOIN outlets o ON o.id = i.outlet_id
    GROUP BY o.name ORDER BY reach DESC LIMIT 5
  ) x
), topics_top AS (
  SELECT string_agg(topic, ', ' ORDER BY n DESC) AS list FROM (
    SELECT lower(t) AS topic, count(*) AS n
    FROM items, unnest(topics) AS t
    GROUP BY 1 ORDER BY n DESC LIMIT 5
  ) y
), heads AS (
  SELECT string_agg(headline, E'\n' ORDER BY tw DESC, created_at) AS list FROM (
    SELECT headline, created_at,
      CASE tier_at_extraction WHEN 1 THEN 3 WHEN 2 THEN 2 WHEN 3 THEN 1 ELSE 0 END AS tw
    FROM items ORDER BY tw DESC, created_at LIMIT 5
  ) z
)
SELECT
  'Total coverage items: ' || c.total || E'\n' ||
  'Tier breakdown — Tier 1: ' || c.t1 || ', Tier 2: ' || c.t2 || ', Tier 3: ' || c.t3 || E'\n' ||
  'Sentiment — positive: ' || c.pos || ', neutral: ' || c.neu || ', mixed: ' || c.mix ||
    ', negative: ' || c.neg || E'\n' ||
  COALESCE('Top outlets by reach: ' || o.list || E'\n', '') ||
  COALESCE('Most-mentioned topics: ' || tt.list || E'\n', '') ||
  COALESCE('Notable headlines:' || E'\n' || h.list, '') AS llm_input
FROM counts c
LEFT JOIN outlets_top o ON true
LEFT JOIN topics_top tt ON true
LEFT JOIN heads h ON true;
