ALTER TABLE scope ALTER COLUMN affiliate_uuid SET NOT NULL;

CREATE INDEX scope_affiliate_uuid_idx ON scope (affiliate_uuid);
