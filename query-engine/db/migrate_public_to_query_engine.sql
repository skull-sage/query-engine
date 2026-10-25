-- One-off move of an existing domain database to the query_engine schema.
--
-- Run it as an administrator (e.g. postgres) against the domain's database, AFTER the roles and
-- the query_engine schema exist (`./gradlew provision` in the service) and BEFORE the new query-engine version first
-- starts. If the engine starts first, it creates an empty query_engine.drafted_query and the old
-- drafts stay behind in query_engine.query_draft.
--
-- It moves the four engine tables from public into query_engine, makes query_engine_owner their
-- owner, renames query_draft to drafted_query, and adds the fetch_size column older tables lack. Drafts and published queries are kept. It is
-- safe to run more than once: a table already in query_engine is left alone.

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['query_draft', 'published_query', 'realm_role', 'realm_group'] LOOP
    IF to_regclass('public.' || t) IS NOT NULL AND to_regclass('query_engine.' || t) IS NULL THEN
      EXECUTE format('ALTER TABLE public.%I SET SCHEMA query_engine', t);
    END IF;
  END LOOP;

  IF to_regclass('query_engine.query_draft') IS NOT NULL AND to_regclass('query_engine.drafted_query') IS NULL THEN
    ALTER TABLE query_engine.query_draft RENAME TO drafted_query;
    ALTER INDEX IF EXISTS query_engine.query_draft_pkey RENAME TO drafted_query_pkey;
  END IF;

  FOREACH t IN ARRAY ARRAY['drafted_query', 'published_query', 'realm_role', 'realm_group'] LOOP
    IF to_regclass('query_engine.' || t) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE query_engine.%I OWNER TO query_engine_owner', t);
    END IF;
  END LOOP;
END $$;

-- Drafts used to expire; they no longer do. Drops the old column from databases created before.
ALTER TABLE IF EXISTS query_engine.drafted_query DROP COLUMN IF EXISTS expires_at;

-- fetch_size (rows per round trip) is part of the tables in schema.sql, which never alters a table. Tables from
-- before that column existed get it here.
ALTER TABLE IF EXISTS query_engine.drafted_query   ADD COLUMN IF NOT EXISTS fetch_size INT;
ALTER TABLE IF EXISTS query_engine.published_query ADD COLUMN IF NOT EXISTS fetch_size INT;
