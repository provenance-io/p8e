ALTER TABLE affiliate ADD COLUMN signing_key_uuid UUID;
ALTER TABLE p8e.affiliate ADD COLUMN key_provider_type TEXT NOT NULL DEFAULT 'DATABASE';
