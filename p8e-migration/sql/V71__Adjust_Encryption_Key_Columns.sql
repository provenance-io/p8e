ALTER TABLE affiliate
    ADD COLUMN encryption_key_uuid UUID,
    ALTER COLUMN encryption_private_key DROP NOT NULL;
