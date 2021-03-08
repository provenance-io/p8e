ALTER TABLE affiliate_connection ADD COLUMN public_key TEXT;

ALTER TABLE scope ADD COLUMN public_key TEXT;

ALTER TABLE envelope ADD COLUMN public_key TEXT;