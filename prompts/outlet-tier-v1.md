---
version: outlet_tier_v1.0
model: claude-sonnet-4
temperature: 0
max_tokens: 200
expected_output_format: json
cache: per_domain
---

# Outlet tier classification prompt

Used only when an outlet is NOT in our curated tier table. The result is cached per outlet domain forever (or until manually invalidated).

## Inputs

- `{{outlet_name}}` — the outlet name as extracted from the article
- `{{domain}}` — the apex domain of the source URL

## Prompt

```
Classify the news outlet "{{outlet_name}}" (domain: {{domain}}) into a tier:

Tier 1: Top-tier national/international publications with broad reach
        (NYT, WSJ, FT, Bloomberg, Reuters, BBC, The Guardian, WaPo, CNN, etc.)
Tier 2: Established trade press, major regional papers, well-known industry
        publications (TechCrunch, The Verge, Wired, Adweek, etc.)
Tier 3: Niche blogs, smaller trade press, local outlets, newsletters

Return JSON: { "tier": 1 | 2 | 3, "rationale": "one sentence" }
```

## Design notes

- Temperature 0 because this is a classification task, not a generative one.
- Cache aggressively: classifications don't change month-to-month. Only re-classify on explicit invalidation.
- The curated table covers ~500 of the most common outlets, so this prompt fires only for long-tail outlets.
- Tier defaults to 3 if classification fails — better to undersell coverage than oversell it in a client report.
- `tier_source = 'llm'` is recorded on the `outlets` row so we can audit later.
