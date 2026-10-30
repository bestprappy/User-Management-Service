-- Bookmarks are private library membership, independent of personal start/end anchors.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE TABLE iam.saved_library_items (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
 content_type varchar(12) NOT NULL CHECK(content_type IN ('PLACE','PLAN')),
 identity varchar(600) NOT NULL,
 name varchar(255) NOT NULL,
 address varchar(512), provider_place_id varchar(512),
 lat double precision, lng double precision,
 country varchar(120), city varchar(120), category varchar(120),
 author_name varchar(255), day_count integer,
 search_text text NOT NULL DEFAULT '',
 saved_at timestamptz NOT NULL DEFAULT now(),
 opened_at timestamptz, deleted_at timestamptz,
 UNIQUE(user_id, content_type, identity),
 CHECK(lat IS NULL OR lat BETWEEN -90 AND 90),
 CHECK(lng IS NULL OR lng BETWEEN -180 AND 180)
);
CREATE INDEX saved_library_user_page ON iam.saved_library_items(user_id,deleted_at,saved_at DESC,id);
CREATE INDEX saved_library_search ON iam.saved_library_items USING gin(search_text public.gin_trgm_ops);
CREATE TABLE iam.saved_library_sources (
 item_id uuid NOT NULL REFERENCES iam.saved_library_items(id) ON DELETE CASCADE,
 token varchar(255) NOT NULL, title varchar(255) NOT NULL,
 PRIMARY KEY(item_id,token)
);
CREATE FUNCTION iam.normalize_library_search(value text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
 SELECT btrim(regexp_replace(regexp_replace(normalize(lower(coalesce(value,'')),NFD),U&'[\0300-\036f]','','g'),'\s+',' ','g'))
$$;
-- Import existing active favorites once, preserving coordinates and personal labels.
INSERT INTO iam.saved_library_items(id,user_id,content_type,identity,name,address,provider_place_id,lat,lng,search_text,saved_at)
SELECT DISTINCT ON (user_id,identity) id,user_id,'PLACE',identity,label,address,provider_place_id,lat,lng,
 iam.normalize_library_search(concat_ws(' ',label,name,address)),created_at
FROM (SELECT *, CASE WHEN provider_place_id IS NOT NULL AND btrim(provider_place_id)<>''
 THEN CASE WHEN provider_place_id LIKE 'GOOGLE:%' THEN 'google:'||substring(provider_place_id FROM 8)
 WHEN provider_place_id LIKE 'ChIJ%' THEN 'google:'||provider_place_id ELSE provider_place_id END
 ELSE 'coordinates:'||round(lat::numeric,6)::text||','||round(lng::numeric,6)::text END identity
 FROM iam.user_saved_places WHERE deleted_at IS NULL) p
ORDER BY user_id,identity,is_default DESC,created_at;

-- Legacy personal-favorite creation also saves the location to the library.
-- Removing library membership never changes an anchor/default, and removing an
-- anchor role does not discard an independently reusable travel bookmark.
CREATE FUNCTION iam.sync_favorite_to_saved_library() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE canonical text;
BEGIN
 IF NEW.deleted_at IS NOT NULL THEN RETURN NEW; END IF;
 canonical := CASE WHEN NEW.provider_place_id IS NOT NULL AND btrim(NEW.provider_place_id)<>''
 THEN CASE WHEN NEW.provider_place_id LIKE 'GOOGLE:%' THEN 'google:'||substring(NEW.provider_place_id FROM 8)
 WHEN NEW.provider_place_id LIKE 'ChIJ%' THEN 'google:'||NEW.provider_place_id ELSE NEW.provider_place_id END
 ELSE 'coordinates:'||round(NEW.lat::numeric,6)::text||','||round(NEW.lng::numeric,6)::text END;
 INSERT INTO iam.saved_library_items(id,user_id,content_type,identity,name,address,provider_place_id,lat,lng,search_text)
 VALUES(gen_random_uuid(),NEW.user_id,'PLACE',canonical,NEW.label,NEW.address,NEW.provider_place_id,NEW.lat,NEW.lng,iam.normalize_library_search(concat_ws(' ',NEW.label,NEW.name,NEW.address)))
 ON CONFLICT(user_id,content_type,identity) DO UPDATE SET deleted_at=NULL,name=excluded.name,address=excluded.address,
 lat=excluded.lat,lng=excluded.lng,provider_place_id=excluded.provider_place_id,search_text=excluded.search_text;
 RETURN NEW;
END $$;
CREATE TRIGGER favorite_to_saved_library AFTER INSERT OR UPDATE OF label,name,address,lat,lng,provider_place_id
 ON iam.user_saved_places FOR EACH ROW EXECUTE FUNCTION iam.sync_favorite_to_saved_library();
