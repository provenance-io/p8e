CREATE TABLE IF NOT EXISTS affiliate(
  affiliate_uuid UUID PRIMARY KEY,
  key_data JSONB NOT NULL
);

ALTER TABLE scope ADD COLUMN affiliate_uuid UUID;
