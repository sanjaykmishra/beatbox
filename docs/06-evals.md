# 06 — Eval set

The single most important non-negotiable in the codebase. If the eval harness is failing, no PR ships. If the eval harness doesn't exist, no customer onboards.

## Why this matters

LLM output quality is invisible until it's catastrophically wrong. One fabricated quote in a client report and the agency loses trust with their client and with us. We can't recover from that with apologies; we can only prevent it with eval discipline.

## Golden set construction

Hand-curate 50 articles across the categories below. For each, hand-write the expected output: correct headline, correct sentiment, an acceptable summary, a list of must-include facts, and a list of must-NOT-include items (hallucinations, fabrications, hyperbole).

| Category | Count | Why |
|---|---|---|
| Standard B2B tech press releases | 10 | The mode of our coverage |
| Tier-1 features (NYT, WSJ, FT) | 10 | Hardest to summarize, highest stakes |
| Negative coverage | 5 | Sentiment failure here is most painful |
| Mixed-sentiment | 5 | Hardest classification edge case |
| Passing-mention | 5 | Subject named once in a broader article |
| Trade publications | 5 | Industry-specific jargon |
| Paywalled (partial text only) | 5 | Real-world failure mode |
| Non-English | 5 | Should fail explicitly, not hallucinate English output |

The articles are stored under `api/src/test/eval/articles/` as static text files. Original URLs are recorded for traceability but the eval runs against the local cached text — we don't depend on the live web.

## Golden file format

YAML, one entry per article. Example:

```yaml
# api/src/test/eval/golden-set.yaml
- id: tc_acme_series_b
  source_file: articles/tc_acme_series_b.txt
  category: standard_b2b
  url: https://techcrunch.com/2025/12/04/acme-raises-30m-series-b
  outlet_name: TechCrunch
  subject_name: Acme Corp
  expected:
    headline: "Acme Corp raises $30M Series B led by Sequoia"
    author: "Sarah Perez"
    publish_date: "2025-12-04"
    sentiment: positive
    subject_prominence: feature
    topics_must_include_any_of: ["funding"]
    must_include_facts:
      - "$30 million" or "$30M"
      - "Sequoia"
      - "Series B"
    must_not_include:
      - "$300 million"      # common transcription error
      - "Series A"
      - any quote not present in source
      - hyperbole: ["groundbreaking", "revolutionary", "tremendous"]
    summary_acceptable_examples:
      - "Acme Corp raised $30M in Series B funding led by Sequoia, intended to accelerate AI product development and grow the engineering team."
      - "TechCrunch reported on Acme Corp's $30M Series B led by Sequoia; the funds will support product expansion and hiring."

- id: wsj_acme_acquisition
  ...
```

Building the golden set is a one-time ~2-day task and should be the first thing done in week 4 of the build plan.

## Metrics

Per item, per prompt version. Run on every PR that touches `prompts/`, the LLM client, or extraction code.

| Metric | Target | Implementation |
|---|---|---|
| Schema compliance | 100% | JSON parses, all required fields present, types correct |
| Field accuracy (headline, author, date) | ≥ 98% | Exact match for date, fuzzy match (Levenshtein < 0.1) for headline/author |
| Sentiment accuracy | ≥ 90% | Class match against ground truth |
| Subject prominence accuracy | ≥ 90% | Class match |
| Summary factual coverage | ≥ 80% | LLM-as-judge: does summary contain `must_include_facts`? |
| Hallucination rate | < 2% | LLM-as-judge: does summary contain `must_not_include` items? |
| Hyperbole rate | 0% | Regex match against forbidden word list |
| Average extraction cost | < $0.05/item | (input tokens + output tokens) × per-token pricing |
| P95 extraction latency | < 8s | End-to-end including article fetch |

## Pre-launch hard gates

Before onboarding the first paying customer, the following MUST be true on the full 50-item golden set:

1. **Schema compliance: 100%** — every output parses and validates.
2. **Hallucination rate: 0** — yes, zero. Lower the bar later when we know what we can absorb. At launch we cannot have any fabricated quote or fact in the eval set.
3. **Sentiment accuracy: ≥ 90%** — across all 50 items.
4. **A human (the founder) has read every executive summary the system has generated to date** and would be willing to send it to a paying client.

If any of these fail, do not onboard. The product is not ready.

## Harness implementation

Lives in `api/src/test/eval/`. JUnit 5 + AssertJ. Run as `./gradlew evalTest`.

Structure:

```
api/src/test/eval/
├── golden-set.yaml
├── articles/
│   ├── tc_acme_series_b.txt
│   ├── wsj_acme_acquisition.txt
│   └── ...
├── EvalRunner.java         ← entry point
├── ExtractionEvalTest.java ← per-item extraction assertions
├── SummaryEvalTest.java    ← executive summary assertions
├── LlmJudge.java           ← Opus-based judge for fact coverage
├── HyperboleDetector.java  ← regex-based forbidden-word check
└── reports/                ← markdown reports written here per run
```

`EvalRunner` runs all golden items through the live extraction pipeline (with cached article text, so no network). It produces a markdown report at `reports/eval-{timestamp}.md` with:

- Per-item pass/fail
- Per-metric summary
- Cost and latency stats
- Diffs of any failures

## LLM-as-judge

For "summary factual coverage" and "hallucination rate," we use a separate Claude Opus call with a strict rubric. The judge prompt:

```
You are evaluating a summary against a known set of facts. Be strict.

Original facts (must all appear in the summary, possibly paraphrased):
{must_include_facts}

Forbidden content (must NOT appear in any form):
{must_not_include}

Generated summary:
{summary}

Return JSON:
{
  "facts_covered": <int 0..N>,
  "facts_total": <int N>,
  "forbidden_violations": [<list of any forbidden items found, with verbatim quotes>],
  "rationale": "one paragraph"
}
```

Judgments are cached by `(item_id, summary_hash)` so unchanged outputs aren't re-judged. Cost: ~$0.05 per judgment, run only on PRs that change LLM-touching code.

## CI integration

GitHub Actions workflow `eval.yml`:

```yaml
on:
  pull_request:
    paths:
      - 'prompts/**'
      - 'api/src/main/java/app/beat/llm/**'
      - 'api/src/main/java/app/beat/extraction/**'
      - 'api/src/main/java/app/beat/render/**'
  schedule:
    - cron: '0 6 * * *'  # nightly

jobs:
  eval:
    steps:
      - run: ./gradlew evalTest
      - uses: actions/upload-artifact@v3
        with:
          path: api/src/test/eval/reports/eval-*.md
      - run: |
          # Fail if any hard gate violated.
          # Job exits non-zero, blocks merge.
```

## Cost guardrails

A full eval run costs roughly:
- 50 items × ~$0.03 extraction = $1.50
- 50 items × ~$0.05 LLM judge = $2.50
- Total per run: ~$4

Run on every relevant PR + nightly. Budget: ~$120/month at our PR cadence. Cheap.

## Growing the golden set

The golden set should grow as we encounter real-world failures. Process:

1. Customer reports a bad extraction or summary.
2. Add the article to the golden set with the correct expected output.
3. Run the eval — it should fail on this new item.
4. Fix the prompt (new version) or the surrounding code.
5. Re-run — passes. PR merges with the new golden item permanently included.

This converts every customer complaint into a regression test. Compounds value over time.

## What's NOT in the eval harness

- **End-to-end UI tests** — those live in `web/src/__tests__/e2e/` and run via Playwright.
- **Backend integration tests** — live in `api/src/test/integration/`.
- **Load tests** — separate project, run pre-launch and quarterly.

The eval harness is specifically about LLM output quality. Don't conflate it with general testing.
