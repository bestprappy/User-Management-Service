-- Additive: photos uploaded by catalog admins. A catalog row refers to one through
-- image_url (/api/vehicle-models/images/<id>), so no existing column changes and the
-- previous release ignores this table. Pixels are re-encoded and bounded like avatars.
CREATE TABLE IF NOT EXISTS iam.vehicle_model_images (
    id           UUID PRIMARY KEY,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    image_bytes  BYTEA       NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 5242880),
    uploaded_by  UUID        REFERENCES iam.users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
