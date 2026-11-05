-- Separate storage preserves the released PLAN/PLACE-only backend after rollback.
CREATE TABLE iam.saved_guide_items (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
 content_type varchar(12) NOT NULL CHECK(content_type='GUIDE'),
 identity varchar(600) NOT NULL,
 name varchar(255) NOT NULL,
 address varchar(512), provider_place_id varchar(512),
 lat double precision, lng double precision,
 country varchar(120), city varchar(120), category varchar(120),
 author_name varchar(255), day_count integer,
 search_text text NOT NULL DEFAULT '',
 saved_at timestamptz NOT NULL DEFAULT now(),
 opened_at timestamptz, deleted_at timestamptz,
 UNIQUE(user_id,content_type,identity)
);
CREATE INDEX saved_guide_user_page ON iam.saved_guide_items(user_id,deleted_at,saved_at DESC,id);
CREATE INDEX saved_guide_search ON iam.saved_guide_items USING gin(search_text public.gin_trgm_ops);
