-- Per-theme label color for palette colors, and permanent deletion.
-- Additive: both columns default to 'auto' (the old best-contrast rule), and
-- the 24 seeded colors take 'white', which is what the client always drew on
-- them. The previous release ignores the new columns and table.
ALTER TABLE iam.palette_colors
    ADD COLUMN text_on_light varchar(5) NOT NULL DEFAULT 'auto' CHECK (text_on_light IN ('auto', 'white', 'black')),
    ADD COLUMN text_on_dark varchar(5) NOT NULL DEFAULT 'auto' CHECK (text_on_dark IN ('auto', 'white', 'black'));

UPDATE iam.palette_colors SET text_on_light = 'white', text_on_dark = 'white'
WHERE id IN ('teal', 'cyan', 'blue', 'indigo', 'violet', 'rose', 'coral', 'amber', 'emerald', 'pine', 'navy', 'slate',
             'plum', 'wine', 'brick', 'rust', 'lime', 'olive', 'gold', 'sky', 'fuchsia', 'mocha', 'stone', 'graphite');

-- A deleted color's id stays reserved here forever: trips, guides and shared
-- snapshots that stored it render the default color instead, and a new color
-- can never take the id over and recolor them.
CREATE TABLE iam.deleted_palette_colors (
    id varchar(40) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9-]{0,39}$'),
    name varchar(40) NOT NULL,
    hex varchar(7) NOT NULL,
    deleted_by uuid REFERENCES iam.users(id),
    deleted_at timestamptz NOT NULL DEFAULT now()
);
