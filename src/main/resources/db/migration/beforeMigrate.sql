-- Flyway callback: runs before every migrate, so it must stay idempotent.
--
-- pg_trgm is installed once per database, and every Navio service shares one
-- database. community-service V1 creates it unqualified while Flyway's
-- search_path is "social", so wherever community migrated first it lives in
-- social. V10 and SavedLibraryRepository address it as public.*, and
-- CREATE EXTENSION IF NOT EXISTS ... WITH SCHEMA public is a silent no-op when
-- it already exists elsewhere. Relocate it instead. pg_trgm is relocatable,
-- community-service never calls its functions, and any index built on it
-- keeps working because indexes reference the operator class by OID.
DO $$
BEGIN
    IF EXISTS (SELECT 1
               FROM pg_extension e
               JOIN pg_namespace n ON n.oid = e.extnamespace
               WHERE e.extname = 'pg_trgm' AND n.nspname <> 'public') THEN
        ALTER EXTENSION pg_trgm SET SCHEMA public;
    END IF;
END
$$;
