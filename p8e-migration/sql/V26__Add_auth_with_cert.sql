-- add certificate_uuid to class resource
ALTER TABLE class_resource ADD COLUMN certificate_uuid UUID;

WITH affiliate_cert_values AS (
  SELECT affiliate_uuid as aid, (key_data#>>'{certificate,uuid,value}')::uuid as cid FROM affiliate
)
UPDATE class_resource SET certificate_uuid = acv.cid FROM affiliate_cert_values acv WHERE acv.aid = affiliate_uuid;

CREATE INDEX class_resource_certificate_uuid_idx on class_resource (certificate_uuid);
ALTER TABLE class_resource ALTER COLUMN certificate_uuid SET NOT NULL;

-- add certificate_uuid to envelope
ALTER TABLE envelope ADD COLUMN certificate_uuid UUID;

WITH affiliate_cert_values AS (
  SELECT s.uuid as sid, (a.key_data#>>'{certificate,uuid,value}')::uuid as cid
  FROM affiliate a
  INNER JOIN scope s on s.affiliate_uuid = a.affiliate_uuid
)
UPDATE envelope SET certificate_uuid = acv.cid FROM affiliate_cert_values acv WHERE acv.sid = scope_uuid;

CREATE INDEX envelope_certificate_uuid_idx on envelope (certificate_uuid);
ALTER TABLE envelope ALTER COLUMN certificate_uuid SET NOT NULL;

-- add certificate_uuid to proto_message
ALTER TABLE proto_message ADD COLUMN certificate_uuid UUID;

WITH affiliate_cert_values AS (
  SELECT affiliate_uuid as aid, (key_data#>>'{certificate,uuid,value}')::uuid as cid FROM affiliate
)
UPDATE proto_message SET certificate_uuid = acv.cid FROM affiliate_cert_values acv WHERE acv.aid = affiliate_uuid;

CREATE INDEX proto_message_certificate_uuid_idx on proto_message (certificate_uuid);
ALTER TABLE proto_message ALTER COLUMN certificate_uuid SET NOT NULL;