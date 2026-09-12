ALTER TABLE iam.user_vehicles
    ADD COLUMN max_ac_kw NUMERIC(7,2),
    ADD COLUMN max_dc_kw NUMERIC(7,2),
    ADD COLUMN starting_battery_pct INTEGER NOT NULL DEFAULT 80,
    ADD COLUMN image_url VARCHAR(2048),
    ADD CONSTRAINT ck_vehicle_ac_power CHECK (max_ac_kw BETWEEN 0 AND 1000),
    ADD CONSTRAINT ck_vehicle_dc_power CHECK (max_dc_kw BETWEEN 0 AND 2000),
    ADD CONSTRAINT ck_vehicle_starting_battery CHECK (starting_battery_pct BETWEEN 0 AND 100);

-- The existing metadata_jsonb holds the immutable, typed catalogue source snapshot.
