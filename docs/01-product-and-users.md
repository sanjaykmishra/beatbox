# 01 — Product and users

## One-liner

Client-ready PR coverage reports in 60 seconds. Paste links, get a branded PDF.

## The problem

PR agencies build monthly coverage reports for every retainer client. Today this means:
- Pull a list of links from email threads, Google Alerts, manual searches.
- Visit each article, copy the headline, take a screenshot, write a one-line summary.
- Eyeball sentiment. Guess at reach. Maybe look up Moz Domain Authority.
- Paste it all into a PowerPoint template, fix formatting, export to PDF.
- Send to the client. Repeat next month.

Time cost: 6–10 hours per client per month. Across 8 clients, that's a person-week per month spent on a deliverable nobody enjoys making and few clients read carefully.

The big PR tools (Cision, Muck Rack, Meltwater) start at $10K+/year and bundle this with a hundred features the small agency doesn't need. Below that price point: spreadsheets and PowerPoint.

## The wedge

Solve only this one job, unreasonably well.

## Target users

**Primary: boutique agency owner / account lead.** 2–10 person agency. 5–20 active retainer clients. Currently uses spreadsheets, Google Alerts, maybe a free tier of Prowly. Builds reports themselves or has a junior do it.

**Secondary: solo freelance PR pro.** 2–8 retainer clients. Same problem, smaller scale, more price-sensitive.

**Not yet: in-house comms teams.** Phase 4. Different buying motion.

**Not ever: PR ops at Fortune 500s.** They have Cision and aren't switching.

## Value proposition by segment

| Segment | Time saved per month | $ value (at $150/hr blended) | Beat price | ROI |
|---|---|---|---|---|
| Solo, 5 clients | ~30 hours | $4,500 | $59 | ~76x |
| Boutique, 12 clients | ~80 hours | $12,000 | $179 | ~67x |

ROI is theatrically high on purpose — pricing is set for adoption while supporting healthy gross margin given full Phase 3 AI cost. See `docs/18-cost-engineering.md` for the cost analysis.

## Differentiation

| | Cision/Muck Rack/Meltwater | ChatGPT + manual | **Beat** |
|---|---|---|---|
| Reporting-native | Bolted on | DIY | Core product |
| Setup time | Weeks | Minutes | Minutes |
| Branded output | Limited | None | Per-agency |
| Price | $10K+/yr | $20/mo | $59–349/mo |
| LLM-native quality | No | Yes (DIY) | Yes (productized) |

## Pricing

| Plan | Price | Limits |
|---|---|---|
| Solo | $59/mo | 1 user, 5 client workspaces, 50 reports/mo |
| Agency | $179/mo | 5 users, 15 client workspaces, unlimited reports |
| Studio | $349/mo | 15 users, 40 client workspaces, unlimited reports, priority support |

- Annual: 15% discount, locked-in pricing for life of subscription.
- 14-day trial, no credit card. After trial, card required.
- No free tier. Bootstrapped means no time for free riders.
- Design partners (first ~10 customers): $20/mo for 6 months, locked-in for life.
- **Existing customers** (those who signed up at $39/$99 in Phase 1) are grandfathered at their original price for 12 months from the price change date. Price change announcement included.
- LLM visibility tracking add-on (Phase 4): +$49/mo per workspace. Optional.

Pricing reflects the cost analysis in `docs/18-cost-engineering.md` — at full Phase 3 + 4 AI volumes, the original $39/$99 pricing produced negative gross margin on heavy users. The new tiers preserve 70%+ gross margin even at the most expensive tier of usage.

## Scope: in vs out

### In scope (Phase 1 MVP)

- Workspace + branding (logo, primary color, agency name on PDFs).
- Client management (CRUD, per-client logo override).
- Report flow: paste URLs → extract → review/edit → generate PDF.
- One report template, well-designed.
- Public share links for reports (read-only, expirable).
- Email/password auth.
- Stripe billing (Checkout + Customer Portal).

### Out of scope (Phase 1)

These are tempting but explicitly deferred:

- Coverage monitoring / alerts on new mentions.
- Journalist contact database.
- Pitch tracking / outreach CRM.
- Multiple report templates / template editor.
- Team activity feeds, mentions, comments.
- Mobile app.
- Slack / Salesforce / HubSpot integrations.
- SSO, SAML, audit logs.
- Multi-language (English only).
- AVE (ad value equivalent) calculations — they're widely mocked and we shouldn't legitimize them.

If a stakeholder asks for any of the above before week 10, the answer is "Phase 2."

## Success metrics

**Phase 1 (week 12):** 20 paying customers, $2K MRR, NPS > 40, monthly churn < 5%.

**Phase 2 (month 7):** $15K MRR, average $120/customer, expansion revenue from team upgrades and annual plans.

**Phase 3 (month 12):** $40K MRR, pitch tracker attach rate > 50% on existing accounts.

## Risks watch list

1. **Output quality.** One bad summary in a client report and the agency stops trusting us. The eval harness (`docs/06-evals.md`) is the mitigation. Treat it as a release gate, not a nice-to-have.
2. **LLM cost creep.** Hard per-customer caps. Cache aggressively (article content hash → extraction result).
3. **Template hell.** Every agency wants their template. We start with one, build a flexible engine in Phase 2 *before* we say yes to too many one-offs.
4. **Disintermediation.** ChatGPT can do a janky version of this. Our moat is workflow, templates, history, integrations — not the LLM call. Each phase deepens the moat.
5. **Paywalled sources.** WSJ, FT, Bloomberg matter most and are hardest to scrape. Budget for a commercial scraping API.
