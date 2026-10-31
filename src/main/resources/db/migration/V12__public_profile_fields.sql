-- =============================================================================
--  Public profile: username, bio, banner, optional travel preferences.
-- =============================================================================
--  Additive only. Every new column is nullable or has a default, so the previous
--  image (which does not map these columns) keeps inserting and reading users
--  if a release rolls back while this schema stays in place. Profiles created by
--  that older image get a username lazily from the application on next sign-in.
--
--  NOTE: V11 (palette text color) is developed on a separate branch. It must be
--  released before or together with this migration; Flyway refuses a lower
--  version that appears after a higher one has been applied.
-- =============================================================================

ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS username VARCHAR(30);
ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS username_changed_at TIMESTAMPTZ;
ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS bio VARCHAR(280);
ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS travel_interests TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS travel_pace VARCHAR(20);
ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS show_travel_preferences BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE iam.users ADD COLUMN IF NOT EXISTS banner_media_id UUID;

-- Usernames are stored lowercase; the format check makes "Alex" and "alex"
-- impossible to hold as two different handles.
ALTER TABLE iam.users ADD CONSTRAINT ck_iam_users_username_format
    CHECK (username IS NULL OR username ~ '^[a-z][a-z0-9_]{2,29}$');
ALTER TABLE iam.users ADD CONSTRAINT ck_iam_users_travel_pace
    CHECK (travel_pace IS NULL OR travel_pace IN ('relaxed', 'balanced', 'packed'));

-- Backfill a readable, unique handle for every existing profile: a slug of the
-- display name, then a numeric suffix when that slug is already taken.
DO $$
DECLARE
    profile RECORD;
    base    TEXT;
    attempt TEXT;
    counter INTEGER;
BEGIN
    FOR profile IN SELECT id, display_name FROM iam.users WHERE username IS NULL ORDER BY created_at, id LOOP
        base := left(regexp_replace(lower(coalesce(profile.display_name, '')), '[^a-z0-9]+', '', 'g'), 20);
        IF base = '' OR base !~ '^[a-z]' THEN
            base := 'traveler' || left(base, 12);
        END IF;
        IF length(base) < 3 THEN
            base := base || 'traveler';
        END IF;
        attempt := base;
        counter := 1;
        WHILE EXISTS (SELECT 1 FROM iam.users WHERE username = attempt) LOOP
            counter := counter + 1;
            attempt := base || counter::TEXT;
        END LOOP;
        UPDATE iam.users SET username = attempt WHERE id = profile.id;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_users_username ON iam.users (username) WHERE username IS NOT NULL;

-- Previous handles keep redirecting to their owner and stay reserved for a
-- holding period, so a shared link cannot silently start pointing at a stranger.
CREATE TABLE IF NOT EXISTS iam.username_history (
    username    VARCHAR(30) NOT NULL,
    user_id     UUID        NOT NULL REFERENCES iam.users (id) ON DELETE CASCADE,
    released_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_iam_username_history PRIMARY KEY (username, user_id)
);
CREATE INDEX IF NOT EXISTS idx_iam_username_history_recent ON iam.username_history (username, released_at DESC);

-- Banner pixels, stored like the avatar: bounded, re-encoded, owner-keyed.
CREATE TABLE IF NOT EXISTS iam.user_banners (
    user_id      UUID PRIMARY KEY REFERENCES iam.users (id) ON DELETE CASCADE,
    id           UUID        NOT NULL UNIQUE,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    image_bytes  BYTEA       NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 5242880),
    UNIQUE (user_id, id)
);
ALTER TABLE iam.users ADD CONSTRAINT user_banner_owned_fk
    FOREIGN KEY (id, banner_media_id) REFERENCES iam.user_banners (user_id, id) DEFERRABLE INITIALLY DEFERRED;
