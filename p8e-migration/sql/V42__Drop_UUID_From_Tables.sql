ALTER TABLE affiliate DROP COLUMN IF EXISTS affiliate_uuid CASCADE;
ALTER TABLE affiliate ADD PRIMARY KEY (public_key);

ALTER TABLE affiliate_connection ADD FOREIGN KEY (public_key) REFERENCES affiliate(public_key);
ALTER TABLE affiliate_connection DROP COLUMN IF EXISTS affiliate_uuid;
ALTER TABLE affiliate_connection DROP COLUMN IF EXISTS certificate_uuid;

ALTER TABLE envelope DROP COLUMN IF EXISTS certificate_uuid;
ALTER TABLE envelope ADD FOREIGN KEY (public_key) REFERENCES affiliate(public_key);

ALTER TABLE scope DROP COLUMN IF EXISTS affiliate_uuid CASCADE;
ALTER TABLE scope ADD FOREIGN KEY (public_key) REFERENCES affiliate(public_key);