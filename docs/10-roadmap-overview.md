# 10 — Roadmap overview (Phases 2–4)

## The arc

Phase 1 was the wedge: one job done unreasonably well. Phases 2–4 expand from that into a platform without losing the focus that made Phase 1 work.

```
PHASE 1 (months 0–3)        PHASE 2 (months 4–7)        PHASE 3 (months 7–12)         PHASE 4 (year 2)
─────────────────────       ─────────────────────       ──────────────────────        ────────────────
Coverage report MVP    →    Deepen reporting       →    Pitch tracker module    →     Platform
Single agency seat          Team + templates +          Closes the loop:              Monitoring,
$2K MRR                     analytics + portal          pitches → coverage            integrations,
                            $15K MRR                    $40K MRR                      enterprise tier
                                                                                      $100K+ MRR
```

The flywheel each phase adds:

- **Phase 1** — every report adds outlets and authors to global tables. Nothing else compounds yet.
- **Phase 2** — every customer's templates and per-client analytics deepen lock-in. Client portal seeds two-sided demand.
- **Phase 3** — every pitch and reply enriches the journalist database. Pitch-coverage attribution becomes proprietary data nobody else has.
- **Phase 4** — every monitoring search and integration multiplies retention and ACV. Enterprise tier funds the next platform investment.

## Phase gates — don't start the next phase until

### Phase 1 → Phase 2

- 20+ paying customers
- $2K+ MRR
- Monthly logo churn < 5%
- NPS ≥ 40
- Eval harness green on the full golden set
- Founder has interviewed at least 10 paying customers about top pain points and has a ranked list

If any of these aren't true at month 3, fix them before adding scope. Phase 2 features amplify Phase 1 quality — they don't compensate for it.

### Phase 2 → Phase 3

- $15K+ MRR
- Average MRR per customer ≥ $99 (i.e. expansion to Agency tier is real)
- Template editor used by 50%+ of agency-tier customers
- Read-only client portal used by 30%+ of customers (i.e. agencies are inviting their own clients)
- Founder has heard "I wish you tracked my pitches too" from at least 8 customers without prompting

That last one is critical. Phase 3 is a major build; we don't start it on a hunch.

### Phase 3 → Phase 4

- $40K+ MRR
- Pitch tracker attach rate (using it weekly) > 50% on existing accounts
- Net revenue retention > 110% (existing customers expand faster than they churn)
- At least 3 inbound enterprise inquiries that we couldn't fully serve in Phase 3
- Internal capacity: at least 3 engineers (we cannot ship Phase 4 with two)

## What each phase delivers (one-line summary)

| Phase | Headline | New surface area |
|---|---|---|
| 1 | "Reports in 60 seconds" | Reporting flow, single template, solo billing |
| 2 | "Now your whole agency can use it" | Templates, teams, analytics, client portal, email inbox |
| 3 | "And we'll tell you which pitches worked" | Pitch CRM, reply tracking, attribution, journalist DB |
| 4 | "Plug Beat into the rest of your stack" | Monitoring, integrations, enterprise auth |

## What each phase deliberately does NOT include

The discipline of saying no is what makes the wedge strategy work. Items below are explicitly out of scope until the named phase, even if a customer asks.

**Not in Phase 2:**
- Pitch tracking (Phase 3 — too big to bolt on while still landing reporting)
- Coverage monitoring (Phase 4 — needs a different infra investment)
- Mobile app (never, in this product cycle — agencies build reports on desktop)

**Not in Phase 3:**
- Coverage monitoring (Phase 4)
- Salesforce/HubSpot integration (Phase 4 — needs platform investment)
- Outbound pitch composition with LLM ("AI pitch writer") — the wedge for that product is different and we'd lose focus

**Not in Phase 4:**
- A separate "in-house comms" SKU (deferred to year 3 if at all — the buyer is too different)
- A public API for customers to build on (deferred until customer demand is loud)
- AI-generated visualizations / branded social cards (a feature, not a phase — slot in opportunistically)

## Rough cost projection per phase

To inform pricing and runway. Numbers are order-of-magnitude.

| Cost category | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|---|
| Anthropic API per customer/mo | $2 | $4 | $8 | $12 |
| Article scraping per customer/mo | $1 | $2 | $3 | $5 |
| Hosting/storage per customer/mo | <$1 | $1 | $2 | $3 |
| **All-in COGS per customer/mo** | **~$4** | **~$7** | **~$13** | **~$20** |

Gross margins should hold at 80%+ across all phases. If they slip, we're either pricing wrong or under-engineering caching.

## Pricing evolution

| Tier | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|---|
| Solo | $39/mo | $49/mo | $59/mo | $69/mo |
| Agency | $99/mo | $149/mo | $199/mo | $249/mo |
| Pro / Studio | — | $299/mo | $399/mo | $499/mo |
| Enterprise | — | — | — | $800–2000/mo (custom) |

Existing customers grandfathered at their original price for 12 months. New tier between Agency and Enterprise opens up in Phase 2 ("Studio" — for 10–25 person agencies that have outgrown Agency).

## Sequencing principle

Within a phase, ship in order of customer-pull intensity. The plans below are presented as a coherent set of features, not a strict build order. The right order is determined by:

1. What design partners are loudest about (qualitative)
2. What new signups churn fastest without (quantitative — track first-week activation per feature)
3. What unlocks the most expansion revenue (quantitative — track ARR growth per feature shipped)

If three customers in a row demand the same feature out of order, ship it. Ignore the planned order.

## Reading the phase docs

Each of `docs/11`, `docs/12`, `docs/13` follows the same structure:

1. **What and why** — the headline change and the customer evidence motivating it.
2. **Features** — each feature with: motivation, data model additions, API additions, UI surface, LLM/prompt impact, key risks, acceptance criteria.
3. **Migration / backwards compat** — what existing data needs to be backfilled or restructured.
4. **Phase gate to next** — repeat of the criteria above for clarity.

Code paths in the phase docs assume the file structure established in `CLAUDE.md`. New tables follow the naming and conventions in `docs/03-data-model.md`. New endpoints follow `docs/04-api-surface.md`. New prompts follow `docs/05-llm-prompts.md`.
