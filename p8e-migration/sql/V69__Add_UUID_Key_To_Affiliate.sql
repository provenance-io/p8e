ALTER TABLE affiliate ADD COLUMN signing_key_uuid UUID;
ALTER TABLE affiliate ADD COLUMN key_provider_type TEXT NOT NULL DEFAULT 'DATABASE';
