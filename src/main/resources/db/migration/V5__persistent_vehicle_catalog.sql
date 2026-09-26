-- Additive: existing garage/trip snapshots and the previous JSON-backed image are unchanged.
CREATE TABLE iam.vehicle_models (
    id varchar(120) PRIMARY KEY,
    make varchar(100) NOT NULL CHECK (length(btrim(make)) > 0),
    model varchar(100) NOT NULL CHECK (length(btrim(model)) > 0),
    trim varchar(100) NOT NULL DEFAULT '',
    year smallint CHECK (year BETWEEN 1900 AND 2200),
    market varchar(2) NOT NULL CHECK (market ~ '^[A-Z]{2}$'),
    battery_capacity_kwh numeric(8,2) CHECK (battery_capacity_kwh > 0),
    battery_capacity_basis varchar(32) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (battery_capacity_basis IN ('MANUFACTURER_DECLARED','USABLE','GROSS','UNKNOWN')),
    range_km numeric(8,2) CHECK (range_km > 0),
    range_standard varchar(16) CHECK (range_standard IN ('NEDC','WLTP','EPA','CLTC')),
    connector_types text[] NOT NULL DEFAULT '{}'
        CHECK (connector_types <@ ARRAY['CCS1','CCS2','TYPE2','J1772','CHADEMO','NACS','GB_T']::text[]),
    max_ac_kw numeric(7,2) CHECK (max_ac_kw BETWEEN 0 AND 1000),
    max_dc_kw numeric(7,2) CHECK (max_dc_kw BETWEEN 0 AND 2000),
    image_url varchar(2048), source_url varchar(2048), verified_at date,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid REFERENCES iam.users(id), updated_by uuid REFERENCES iam.users(id),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_vehicle_model_publishable CHECK (status <> 'PUBLISHED' OR
        (battery_capacity_kwh IS NOT NULL AND range_km IS NOT NULL AND range_standard IS NOT NULL
        AND cardinality(connector_types) > 0 AND source_url IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_vehicle_model_identity ON iam.vehicle_models
    (lower(btrim(make)), lower(btrim(model)), lower(btrim(trim)), coalesce(year,0), market);
CREATE INDEX ix_vehicle_models_status_market ON iam.vehicle_models (status, market, make, model, id);

-- Frozen copy of the existing Thailand catalog. Never re-read mutable JSON at migration time.
INSERT INTO iam.vehicle_models
    (id,make,model,trim,year,market,battery_capacity_kwh,battery_capacity_basis,range_km,range_standard,
     connector_types,max_ac_kw,max_dc_kw,image_url,source_url,verified_at,status)
VALUES
('th-byd-atto-3-extended-2026','BYD','ATTO 3','Extended',2026,'TH',60.48,'MANUFACTURER_DECLARED',480,'NEDC',
 ARRAY['TYPE2','CCS2'],7,88,'/images/vehicles/byd-atto-3.png',
 'https://www.reverautomotive.com/media/models/new-atto3/brochure/bydatto3_MY2026.pdf','2026-09-12','PUBLISHED'),
('th-byd-dolphin-extended-60-48','BYD','DOLPHIN','Extended Range',NULL,'TH',60.48,'MANUFACTURER_DECLARED',490,'NEDC',
 ARRAY['TYPE2','CCS2'],7,80,'/images/vehicles/byd-dolphin.png',
 'https://www.reverautomotive.com/model/new-dolphin/overview','2026-09-12','PUBLISHED'),
('th-byd-seal-premium-82-56','BYD','SEAL','Premium RWD',NULL,'TH',82.56,'MANUFACTURER_DECLARED',650,'NEDC',
 ARRAY['TYPE2','CCS2'],NULL,150,'/images/vehicles/byd-seal.png',
 'https://www.reverautomotive.com/model/seal/overview','2026-09-12','PUBLISHED');
