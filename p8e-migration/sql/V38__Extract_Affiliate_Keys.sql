ALTER TABLE affiliate ADD COLUMN public_key TEXT;
ALTER TABLE affiliate ADD COLUMN private_key TEXT;
ALTER TABLE affiliate ADD COLUMN encryption_public_key TEXT;
ALTER TABLE affiliate ADD COLUMN encryption_private_key TEXT;

-- Convert the data for affiliates
update affiliate set
   public_key = key_data->'signingKeyPair'->>'publicKeyPem',
   private_key = key_data->'signingKeyPair'->>'privateKeyPem',
   encryption_public_key = key_data->'certificateKeyPairs'->0->'encryptionKeyPair'->>'publicKeyPem',
   encryption_private_key = key_data->'certificateKeyPairs'->0->'encryptionKeyPair'->>'privateKeyPem';