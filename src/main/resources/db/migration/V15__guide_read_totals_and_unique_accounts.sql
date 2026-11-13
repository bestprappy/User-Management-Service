-- Keep the existing daily view totals. Historical daily dedup cannot establish
-- lifetime account uniqueness, so the account ledger starts at this release.
CREATE TABLE iam.guide_unique_account_views (
    source varchar(8) NOT NULL CHECK (source IN ('CURATED','TRAVELER')),
    guide_id uuid NOT NULL,
    account_hash varchar(64) NOT NULL,
    first_view_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, guide_id, account_hash)
);
CREATE INDEX guide_unique_account_recent_idx ON iam.guide_unique_account_views(first_view_at);

-- A reader generates one random request ID per visible page opening. It is not
-- a guest identifier; it only makes retries/React StrictMode idempotent.
CREATE TABLE iam.guide_read_requests (
    source varchar(8) NOT NULL CHECK (source IN ('CURATED','TRAVELER')),
    guide_id uuid NOT NULL,
    read_id uuid NOT NULL,
    read_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, guide_id, read_id)
);
CREATE INDEX guide_read_requests_expiry_idx ON iam.guide_read_requests(read_at);
