-- Source is part of every identity: curated and traveler UUIDs may overlap.
CREATE TABLE iam.guide_daily_views (
    source varchar(8) NOT NULL CHECK (source IN ('CURATED','TRAVELER')),
    guide_id uuid NOT NULL,
    view_day date NOT NULL,
    views bigint NOT NULL DEFAULT 0 CHECK (views >= 0),
    PRIMARY KEY (source,guide_id,view_day)
);
CREATE TABLE iam.guide_view_dedup (
    source varchar(8) NOT NULL CHECK (source IN ('CURATED','TRAVELER')),
    guide_id uuid NOT NULL,
    viewer_hash varchar(64) NOT NULL,
    view_day date NOT NULL,
    PRIMARY KEY (source,guide_id,viewer_hash,view_day)
);
CREATE INDEX guide_view_dedup_day_idx ON iam.guide_view_dedup(view_day);
CREATE TABLE iam.guide_view_budgets (
    viewer_hash varchar(64) NOT NULL,
    view_day date NOT NULL,
    views integer NOT NULL DEFAULT 0 CHECK (views BETWEEN 0 AND 10000),
    PRIMARY KEY (viewer_hash,view_day)
);
CREATE INDEX guide_view_budgets_day_idx ON iam.guide_view_budgets(view_day);
