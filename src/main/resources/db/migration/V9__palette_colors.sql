-- Admin-managed color palette shared by trip sections and guide categories.
-- Additive: trips store the color id in trip.list_block.block_color and guides
-- in their jsonb document, so seeding the 24 existing ids keeps every saved
-- reference valid. Colors are never deleted, only retired, so an id that was
-- ever saved keeps resolving to the same hex.
CREATE TABLE iam.palette_colors (
    id varchar(40) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9-]{0,39}$'),
    name varchar(40) NOT NULL CHECK (length(btrim(name)) > 0),
    hex varchar(7) NOT NULL CHECK (hex ~ '^#[0-9a-f]{6}$'),
    sort_order integer NOT NULL,
    retired_at timestamptz,
    created_by uuid REFERENCES iam.users(id),
    updated_by uuid REFERENCES iam.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_palette_color_hex ON iam.palette_colors (hex);
CREATE UNIQUE INDEX uq_palette_color_name ON iam.palette_colors (lower(btrim(name)));

-- Frozen copy of the client's built-in palette (sRGB of each OKLCH token).
INSERT INTO iam.palette_colors (id, name, hex, sort_order) VALUES
('teal', 'Teal', '#00ae84', 10),
('cyan', 'Cyan', '#00b2b2', 20),
('blue', 'Blue', '#008cdf', 30),
('indigo', 'Indigo', '#5d67ee', 40),
('violet', 'Violet', '#8654cc', 50),
('rose', 'Rose', '#d83e89', 60),
('coral', 'Coral', '#f0584b', 70),
('amber', 'Amber', '#ea8a18', 80),
('emerald', 'Emerald', '#009342', 90),
('pine', 'Pine', '#00603c', 100),
('navy', 'Navy', '#005189', 110),
('slate', 'Slate', '#3b4372', 120),
('plum', 'Plum', '#613888', 130),
('wine', 'Wine', '#972767', 140),
('brick', 'Brick', '#ac312a', 150),
('rust', 'Rust', '#a94700', 160),
('lime', 'Lime', '#56932b', 170),
('olive', 'Olive', '#677014', 180),
('gold', 'Gold', '#aa7e00', 190),
('sky', 'Sky', '#089ac3', 200),
('fuchsia', 'Fuchsia', '#b243ba', 210),
('mocha', 'Mocha', '#7c5336', 220),
('stone', 'Stone', '#766d62', 230),
('graphite', 'Graphite', '#4e535b', 240);
