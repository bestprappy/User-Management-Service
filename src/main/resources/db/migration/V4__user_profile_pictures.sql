CREATE TABLE iam.user_pictures (
    user_id UUID PRIMARY KEY REFERENCES iam.users(id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN ('image/png','image/jpeg')),
    image_bytes BYTEA NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 5242880),
    UNIQUE(user_id, id)
);
-- Previous identifiers were accepted without a managed image record.
UPDATE iam.users SET avatar_media_id = NULL WHERE avatar_media_id IS NOT NULL;
ALTER TABLE iam.users ADD CONSTRAINT user_avatar_owned_fk
    FOREIGN KEY(id, avatar_media_id) REFERENCES iam.user_pictures(user_id, id) DEFERRABLE INITIALLY DEFERRED;
