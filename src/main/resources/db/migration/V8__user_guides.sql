-- Independent traveler library. The previous release continues using iam.guides unchanged.
CREATE TABLE iam.user_guides (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    country varchar(100) NOT NULL DEFAULT '',
    country_code varchar(2) CHECK (country_code ~ '^[A-Z]{2}$'),
    destination_provider varchar(16) NOT NULL CHECK (destination_provider IN ('GOOGLE','MAPBOX')),
    destination_place_id varchar(255) NOT NULL CHECK (length(btrim(destination_place_id)) > 0),
    destination_name varchar(200) NOT NULL,
    destination_lat double precision NOT NULL CHECK (destination_lat BETWEEN -90 AND 90),
    destination_lng double precision NOT NULL CHECK (destination_lng BETWEEN -180 AND 180),
    description text NOT NULL DEFAULT '' CHECK (length(description) <= 4000),
    cover_place_id varchar(300) NOT NULL,
    content jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid REFERENCES iam.users(id) ON DELETE SET NULL,
    updated_by uuid REFERENCES iam.users(id) ON DELETE SET NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_guides_published_at CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX ix_user_guides_owner ON iam.user_guides (owner_id, updated_at DESC, id);
CREATE INDEX ix_user_guides_published ON iam.user_guides (status, published_at DESC, id);
CREATE INDEX ix_user_guides_destination ON iam.user_guides (destination_provider, destination_place_id) WHERE status = 'PUBLISHED';
