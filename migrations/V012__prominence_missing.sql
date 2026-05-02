-- Extend subject_prominence CHECK constraints to accept 'missing'.
--
-- v1.0–v1.2 prompts had only feature/mention/passing. extraction-v1.3 (article) and
-- social-extraction-v1.1 added 'missing' for off-topic items the subject doesn't appear in,
-- with a runtime guard in SummaryService that fires when feature+mention+passing == 0. The
-- worker tries to write 'missing' on the first re-extraction under the new prompts, which
-- crashed against the old CHECK with violates check constraint
-- "social_mentions_subject_prominence_check". Same constraint exists on coverage_items
-- (V001) — fixing both here so the article side doesn't trip later.
--
-- Postgres requires DROP + ADD; you can't ALTER an existing CHECK in place. Both columns
-- remain nullable and additively accept the new value; existing rows are unaffected.

ALTER TABLE coverage_items
  DROP CONSTRAINT IF EXISTS coverage_items_subject_prominence_check;
ALTER TABLE coverage_items
  ADD CONSTRAINT coverage_items_subject_prominence_check
  CHECK (subject_prominence IS NULL
         OR subject_prominence IN ('feature', 'mention', 'passing', 'missing'));

ALTER TABLE social_mentions
  DROP CONSTRAINT IF EXISTS social_mentions_subject_prominence_check;
ALTER TABLE social_mentions
  ADD CONSTRAINT social_mentions_subject_prominence_check
  CHECK (subject_prominence IS NULL
         OR subject_prominence IN ('feature', 'mention', 'passing', 'missing'));
