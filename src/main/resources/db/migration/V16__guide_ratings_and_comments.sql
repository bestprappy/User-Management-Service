CREATE TABLE iam.guide_feedback_subjects (
    id uuid PRIMARY KEY,
    source varchar(8) NOT NULL CHECK (source IN ('CURATED','TRAVELER')),
    guide_id uuid NOT NULL,
    UNIQUE(source,guide_id)
);
CREATE TABLE iam.guide_rating (
    publication_id uuid NOT NULL REFERENCES iam.guide_feedback_subjects(id),
    user_id uuid NOT NULL,
    score integer NOT NULL CHECK(score BETWEEN 1 AND 5),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(publication_id,user_id)
);
CREATE TABLE iam.guide_comment (
    id uuid PRIMARY KEY,
    publication_id uuid NOT NULL REFERENCES iam.guide_feedback_subjects(id),
    parent_id uuid,
    user_id uuid NOT NULL,
    author_name varchar(100) NOT NULL,
    content varchar(1500) NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 0,
    UNIQUE(id,publication_id),
    FOREIGN KEY(parent_id,publication_id) REFERENCES iam.guide_comment(id,publication_id),
    CHECK(parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX guide_comment_threads ON iam.guide_comment(publication_id,created_at DESC,id DESC) WHERE parent_id IS NULL;
CREATE INDEX guide_comment_replies ON iam.guide_comment(publication_id,parent_id,created_at,id) WHERE parent_id IS NOT NULL;
CREATE TABLE iam.guide_comment_report (
    comment_id uuid NOT NULL REFERENCES iam.guide_comment(id),
    reporter_id uuid NOT NULL,
    reason varchar(30) NOT NULL CHECK(reason IN ('SPAM','HARASSMENT','INAPPROPRIATE','OTHER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(comment_id,reporter_id)
);
