-- -----------------------------------------------------------------------------
-- iam.user_saved_places — the user's reusable personal anchors (home, work, ...)
--
-- These rows are PERSONAL CONTEXT, not itinerary content. A trip day may point
-- at one as its start or end anchor, but the trip service stores a *copy* of the
-- resolved coordinates rather than a foreign key, for the same reason it copies
-- vehicles: editing "Home" later must not silently rewrite a trip that was
-- planned from the old address.
--
-- Because a row here can hold a real home address, anything that publishes or
-- shares a trip must strip anchors of kind SAVED_PLACE instead of serialising
-- them. See trip-planning-service V9__day_anchors.sql.
-- -----------------------------------------------------------------------------
CREATE TABLE iam.user_saved_places
(
    id         UUID           PRIMARY KEY,
    user_id    UUID           NOT NULL,
    label      VARCHAR(80)    NOT NULL,
    kind       VARCHAR(20)    NOT NULL DEFAULT 'CUSTOM',
    name       VARCHAR(255)   NOT NULL,
    address    VARCHAR(512),
    lat        DOUBLE PRECISION NOT NULL,
    lng        DOUBLE PRECISION NOT NULL,
    provider_place_id VARCHAR(512),
    is_default BOOLEAN        NOT NULL DEFAULT FALSE,
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_iam_user_saved_places_user FOREIGN KEY (user_id)
        REFERENCES iam.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_iam_user_saved_places_kind
        CHECK (kind IN ('HOME', 'WORK', 'CUSTOM')),
    CONSTRAINT ck_iam_user_saved_places_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_iam_user_saved_places_lng CHECK (lng BETWEEN -180 AND 180),
    CONSTRAINT ck_iam_user_saved_places_label CHECK (length(btrim(label)) > 0)
);

CREATE INDEX idx_iam_user_saved_places_user_deleted
    ON iam.user_saved_places (user_id, deleted_at);

-- At most one active default start point per user, so "where do I usually start"
-- has exactly one answer the planner can preselect without guessing.
CREATE UNIQUE INDEX uq_iam_user_saved_places_user_default
    ON iam.user_saved_places (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;

-- HOME and WORK are singletons; CUSTOM is unconstrained.
CREATE UNIQUE INDEX uq_iam_user_saved_places_user_kind
    ON iam.user_saved_places (user_id, kind)
    WHERE kind IN ('HOME', 'WORK') AND deleted_at IS NULL;
